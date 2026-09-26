#!/usr/bin/env python3
"""Run and compare Metallum benchmarks.

  scripts/bench.py run  [--label L] [--runs N] [--duration S] [--warmup S] [--render-distance N]
  scripts/bench.py show [LABEL|FILE ...]
  scripts/bench.py compare BASELINE CANDIDATE
  scripts/bench.py ab [REF] [--label L] [--runs N]    # interleaved REF (default HEAD) vs working tree
  scripts/bench.py profile                            # one run under async-profiler + render-thread breakdown
  scripts/bench.py analyze FILE.collapsed [--thread NAME]

`run` launches `./gradlew runBenchmark` N times (a fixed-seed world, camera spin, then quit) and prints
the median of the runs. The first time, it generates the world in a longer preparation run and keeps it
as a template (run/metallum-bench/templates/); each measured run starts from a fresh copy of it. Labels default to the current git commit. BASELINE/CANDIDATE are labels (all
run/metallum-bench/<label>-*.json files, medianed) or paths to individual result files.

Machine speed drifts by several percent over an hour (thermals, background load), so results recorded at
different times are not comparable to within a few percent. For judging a change, prefer `ab`: it builds
REF in a git worktree and alternates baseline/candidate runs so both sides see the same conditions.
"""
import argparse
import glob
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "run", "metallum-bench")
WORLD = os.path.join(ROOT, "run", "saves", "metallum-bench")
TEMPLATES = os.path.join(RESULTS, "templates")
PREPARE_WARMUP = 60

# (key path, display name, True if higher is better)
METRICS = [
    ("summary.avgFps", "avg FPS", True),
    ("summary.onePercentLowFps", "1% low FPS", True),
    ("summary.frameMs.p50", "frame ms p50", False),
    ("summary.frameMs.p90", "frame ms p90", False),
    ("summary.frameMs.p99", "frame ms p99", False),
    ("summary.frameMs.max", "frame ms max", False),
    ("summary.cpuMsPerFrame", "CPU ms/frame", False),
    ("summary.submitWaitMsPerFrame", "GPU wait ms/frame", False),
    ("summary.gpuMsPerSubmit", "GPU ms/submit", False),
    ("summary.gpuBusyPercent", "GPU busy %", None),
    ("summary.perFrame.drawCalls", "draws/frame", False),
    ("summary.perFrame.renderEncoders", "render encoders/frame", False),
    ("summary.perFrame.blitEncoders", "blit encoders/frame", False),
    ("summary.perFrame.pipelineBinds", "pipeline binds/frame", False),
    ("summary.perFrame.bufferBinds", "buffer binds/frame", False),
    ("summary.perFrame.textureBinds", "texture binds/frame", False),
    ("summary.perFrame.samplerBinds", "sampler binds/frame", False),
    ("summary.perFrame.texelViewsCreated", "texel views/frame", False),
    ("summary.perFrame.uploadBytes", "upload bytes/frame", False),
    ("summary.perFrame.pipelineCompiles", "pipeline compiles/frame", False),
    ("summary.perFrame.buffersCreated", "MTLBuffers created/frame", False),
]


def get(data, path):
    for key in path.split("."):
        if not isinstance(data, dict) or key not in data:
            return None
        data = data[key]
    return data


def load(spec):
    """A label (every matching run) or a single result file -> list of result dicts."""
    if os.path.isfile(spec):
        paths = [spec]
    else:
        paths = sorted(glob.glob(os.path.join(RESULTS, f"{spec}-[0-9]*-[0-9]*.json")))
        if not paths:
            sys.exit(f"no results for '{spec}' in {RESULTS}")
    results = []
    for path in paths:
        with open(path) as f:
            data = json.load(f)
        if "error" in data or "summary" not in data:
            print(f"skipping failed run {os.path.basename(path)}: {data.get('error', 'no summary')}", file=sys.stderr)
            continue
        results.append(data)
    if not results:
        sys.exit(f"no successful results for '{spec}'")
    return results


def median(results, path):
    values = [v for v in (get(r, path) for r in results) if v is not None]
    return statistics.median(values) if values else None


def spread(results, path):
    """Relative spread (max-min)/median across runs, as a noise indicator."""
    values = [v for v in (get(r, path) for r in results) if v is not None]
    if len(values) < 2 or not statistics.median(values):
        return None
    return (max(values) - min(values)) / statistics.median(values) * 100


def fmt(value):
    if value is None:
        return "-"
    return f"{value:,.0f}" if abs(value) >= 1000 else f"{value:,.2f}"


def describe(name, results):
    env = results[0]["environment"]
    commits = sorted({r.get("commit", "?") for r in results})
    return (f"{name}: {len(results)} run(s), commit {', '.join(commits)}, {env.get('backend')} on {env.get('device')}, "
            f"{env.get('framebufferWidth')}x{env.get('framebufferHeight')}, RD {env.get('renderDistance')}")


def show(specs):
    for spec in specs:
        results = load(spec)
        print(describe(spec, results))
        for path, name, _ in METRICS:
            value = median(results, path)
            if value is None:
                continue
            noise = spread(results, path)
            print(f"  {name:<26}{fmt(value):>12}" + (f"   ±{noise / 2:.1f}%" if noise is not None else ""))
        print()


def read_pixels(path):
    """(width, height, rows of pixel bytes, bytes per pixel), via macOS `sips` converting the PNG to an uncompressed BMP."""
    out = os.path.join(RESULTS, ".diff.bmp")
    subprocess.check_call(["sips", "-s", "format", "bmp", path, "--out", out], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    with open(out, "rb") as f:
        data = f.read()
    os.remove(out)
    offset = int.from_bytes(data[10:14], "little")
    width = int.from_bytes(data[18:22], "little", signed=True)
    height = abs(int.from_bytes(data[22:26], "little", signed=True))
    bpp = int.from_bytes(data[28:30], "little") // 8
    stride = (width * bpp + 3) & ~3
    rows = [data[offset + y * stride:offset + y * stride + width * bpp] for y in range(height)]
    return width, height, rows, bpp


def image_diff(a_path, b_path, threshold=8):
    """Fraction of pixels whose largest channel difference exceeds `threshold`, and the largest difference."""
    aw, ah, a_rows, bpp = read_pixels(a_path)
    bw, bh, b_rows, b_bpp = read_pixels(b_path)
    if (aw, ah, bpp) != (bw, bh, b_bpp):
        return 1.0, 255
    differing, worst = 0, 0
    for a_row, b_row in zip(a_rows, b_rows):
        if a_row == b_row:
            continue
        for x in range(0, len(a_row), bpp):
            d = max(abs(a_row[x + i] - b_row[x + i]) for i in range(3))
            if d > threshold:
                differing += 1
            worst = max(worst, d)
    return differing / (aw * ah), worst


def screenshots(spec):
    if os.path.isfile(spec):
        paths = [spec.replace(".json", ".png")]
    else:
        paths = sorted(glob.glob(os.path.join(RESULTS, f"{spec}-[0-9]*-[0-9]*.png")))
    return [p for p in paths if os.path.isfile(p)]


def compare_images(base_spec, cand_spec):
    base, cand = screenshots(base_spec), screenshots(cand_spec)
    if not base or not cand:
        print("\nscreenshots: not available for both sides")
        return
    # Baseline-vs-baseline shows how deterministic the scene is; the candidate should be no further off.
    noise = image_diff(base[0], base[1]) if len(base) > 1 else None
    diff = image_diff(base[0], cand[0])
    line = f"\nscreenshot diff vs baseline: {100 * diff[0]:.3f}% of pixels differ (max channel delta {diff[1]})"
    if noise is not None:
        line += f"; baseline run-to-run: {100 * noise[0]:.3f}% (max {noise[1]})"
    print(line)
    if diff[0] > max(0.001, 2 * (noise[0] if noise else 0)):
        print(f"WARNING: the candidate renders differently. Compare {os.path.relpath(base[0], ROOT)} "
              f"and {os.path.relpath(cand[0], ROOT)}")


def compare(base_spec, cand_spec):
    base, cand = load(base_spec), load(cand_spec)
    print(describe("baseline ", base))
    print(describe("candidate", cand))
    for label, results in (("baseline", base), ("candidate", cand)):
        env = results[0]["environment"]
        key = (env.get("framebufferWidth"), env.get("framebufferHeight"), env.get("renderDistance"), env.get("seed"))
        if label == "baseline":
            base_key = key
        elif key != base_key:
            print("WARNING: runs used different resolution/render distance/seed; comparison is not like-for-like")
    print()
    print(f"  {'metric':<26}{'baseline':>12}{'candidate':>12}{'change':>10}   noise")
    for path, name, higher_better in METRICS:
        b, c = median(base, path), median(cand, path)
        if b is None and c is None:
            continue
        change = ""
        if b and c is not None:
            pct = (c - b) / b * 100
            change = f"{pct:+.1f}%"
            noise = max(spread(base, path) or 0, spread(cand, path) or 0) / 2
            if higher_better is not None and abs(pct) > max(noise, 1.0):
                change += " ✓" if (pct > 0) == higher_better else " ✗"
        else:
            noise = 0
        print(f"  {name:<26}{fmt(b):>12}{fmt(c):>12}{change:>10}   " + (f"±{noise:.1f}%" if noise else ""))
    print("\n✓/✗ = better/worse by more than the run-to-run noise (or 1%). Use --runs 3+ for a usable noise estimate.")
    compare_images(base_spec, cand_spec)


def java_home():
    if os.environ.get("JAVA_HOME"):
        return os.environ["JAVA_HOME"]
    try:
        return subprocess.check_output(["/usr/libexec/java_home", "-v", "25"], stderr=subprocess.DEVNULL, text=True).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    brew = "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
    return brew if os.path.isdir(brew) else None


def current_commit():
    try:
        commit = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, text=True).strip()
        dirty = subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=ROOT, text=True).strip()
        return commit + ("-dirty" if dirty else "")
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "run"


def count_chunks(world):
    """Number of generated overworld chunks, read from the region file headers."""
    total = 0
    for path in glob.glob(os.path.join(world, "**", "overworld", "region", "*.mca"), recursive=True):
        with open(path, "rb") as f:
            header = f.read(4096)
        total += sum(1 for i in range(0, len(header), 4) if header[i:i + 4] != b"\0\0\0\0")
    return total


def launch(env, label, args, warmup, duration, project=ROOT):
    """One game launch of the checkout in `project`. Returns the result path (in RESULTS), or exits on failure."""
    cmd = [os.path.join(project, "gradlew"), "runBenchmark", "--console=plain", "-q",
           f"-Pbench.label={label}", f"-Pbench.duration={duration}", f"-Pbench.warmup={warmup}",
           f"-Pbench.renderDistance={args.render_distance}", f"-Pbench.width={args.width}", f"-Pbench.height={args.height}"]
    os.makedirs(RESULTS, exist_ok=True)
    log_path = os.path.join(RESULTS, f"{label}.log")
    project_results = os.path.join(project, "run", "metallum-bench")
    started = time.time()
    with open(log_path, "w") as log:
        code = subprocess.call(cmd, cwd=project, env=env, stdout=log, stderr=subprocess.STDOUT)
    new = [p for p in glob.glob(os.path.join(project_results, f"{label}-[0-9]*-[0-9]*.json")) if os.path.getmtime(p) >= started]
    if code != 0 or not new:
        sys.exit(f"benchmark run failed (exit {code}); see {log_path}")
    result = max(new, key=os.path.getmtime)
    if project != ROOT:
        screenshot = result.replace(".json", ".png")
        if os.path.isfile(screenshot):
            shutil.move(screenshot, os.path.join(RESULTS, os.path.basename(screenshot)))
        moved = os.path.join(RESULTS, os.path.basename(result))
        shutil.move(result, moved)
        result = moved
    return result


def world_dir(project):
    return os.path.join(project, "run", "saves", "metallum-bench")


def prepare_template(env, args, template):
    """Generate the world once, with a long warmup so every chunk in render distance exists, and snapshot it."""
    print(f"preparing world template for render distance {args.render_distance} (~{PREPARE_WARMUP + 35}s)...", flush=True)
    shutil.rmtree(WORLD, ignore_errors=True)
    os.remove(launch(env, "_prepare", args, PREPARE_WARMUP, 5))
    shutil.rmtree(template, ignore_errors=True)
    shutil.copytree(WORLD, template)
    print(f"      -> {os.path.relpath(template, ROOT)} ({count_chunks(template)} chunks)")


def setup(args):
    """Java environment plus the pre-generated world template for this render distance."""
    env = dict(os.environ)
    home = java_home()
    if home:
        env["JAVA_HOME"] = home
    # Every measured run starts from an identical copy of a fully generated world, so no run pays for
    # terrain generation and none inherits state (time, entities, player) saved by the previous one.
    template = os.path.join(TEMPLATES, f"world-rd{args.render_distance}")
    if args.regenerate or not os.path.isdir(template):
        prepare_template(env, args, template)
    return env, template


def measure(env, args, template, label, project=ROOT):
    world = world_dir(project)
    shutil.rmtree(world, ignore_errors=True)
    shutil.copytree(template, world)
    result = launch(env, label, args, args.warmup, args.duration, project)
    print(f"      -> {os.path.relpath(result, ROOT)}", flush=True)
    generated = count_chunks(world) - count_chunks(template)
    if generated > 0:
        print(f"      WARNING: {generated} new chunks were generated during this run; "
              f"regenerate the template with --regenerate", file=sys.stderr)
    return result


def run(args):
    label = args.label or current_commit()
    env, template = setup(args)
    for i in range(args.runs):
        print(f"[{i + 1}/{args.runs}] running benchmark '{label}' (~{args.warmup + args.duration + 25}s)...", flush=True)
        measure(env, args, template, label)
        if i + 1 < args.runs and args.cooldown:
            time.sleep(args.cooldown)
    print()
    show([label])


def baseline_worktree(ref):
    """A detached git worktree of `ref` (kept under run/, so its Gradle caches survive between A/B runs)."""
    path = os.path.join(RESULTS, "baseline-worktree")
    try:
        commit = subprocess.check_output(["git", "rev-parse", "--short", f"{ref}^{{commit}}"], cwd=ROOT, text=True).strip()
    except subprocess.CalledProcessError:
        sys.exit(f"unknown git ref '{ref}'")
    if not os.path.isdir(path):
        subprocess.check_call(["git", "worktree", "add", "--quiet", "--detach", path, commit], cwd=ROOT)
    else:
        subprocess.check_call(["git", "checkout", "--quiet", "--force", "--detach", commit], cwd=path)
    if not os.path.isfile(os.path.join(path, "src", "main", "java", "com", "metallum", "bench", "Benchmark.java")):
        sys.exit(f"{ref} ({commit}) predates the benchmark harness and can't be measured")
    return path, commit


def ab(args):
    """Interleave baseline and candidate runs (ABBA...) so both see the same thermal and background conditions."""
    label = args.label or current_commit()
    base_label = f"{label}-base"
    env, template = setup(args)
    base_path, base_commit = baseline_worktree(args.baseline)
    print(f"A/B: baseline {args.baseline} ({base_commit}) vs working tree, {args.runs} run(s) each", flush=True)
    for label_glob in (label, base_label):
        for old in glob.glob(os.path.join(RESULTS, f"{label_glob}-[0-9]*-[0-9]*.json")):
            os.remove(old)
    sides = [("baseline", base_label, base_path), ("candidate", label, ROOT)]
    total = 2 * args.runs
    step = 0
    for i in range(args.runs):
        for name, side_label, project in (sides if i % 2 == 0 else sides[::-1]):
            step += 1
            print(f"[{step}/{total}] {name} (~{args.warmup + args.duration + 25}s)...", flush=True)
            measure(env, args, template, side_label, project)
            if step < total and args.cooldown:
                time.sleep(args.cooldown)
    print()
    compare(base_label, label)


def profile(args):
    """One run with async-profiler attached for exactly the recorded window, then a CPU breakdown."""
    asprof = shutil.which("asprof")
    if not asprof:
        sys.exit("async-profiler not found; install it with `brew install async-profiler`")
    label = args.label or f"profile-{current_commit()}"
    env, template = setup(args)
    world = world_dir(ROOT)
    shutil.rmtree(world, ignore_errors=True)
    shutil.copytree(template, world)

    os.makedirs(RESULTS, exist_ok=True)
    log_path = os.path.join(RESULTS, f"{label}.log")
    out = os.path.join(RESULTS, f"{label}-{time.strftime('%Y%m%d-%H%M%S')}.collapsed")
    cmd = [os.path.join(ROOT, "gradlew"), "runBenchmark", "--console=plain", "-q",
           f"-Pbench.label={label}", f"-Pbench.duration={args.duration}", f"-Pbench.warmup={args.warmup}",
           f"-Pbench.renderDistance={args.render_distance}", f"-Pbench.width={args.width}", f"-Pbench.height={args.height}"]
    print(f"profiling '{label}' (~{args.warmup + args.duration + 25}s)...", flush=True)
    pattern = re.compile(r"\[metallum-bench\] recording for \d+s \(pid (\d+)\)")
    profiler = None
    with open(log_path, "w") as log:
        game = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        while game.poll() is None:
            if profiler is None:
                with open(log_path) as f:
                    match = pattern.search(f.read())
                if match:
                    # Stop early so the sample never includes shutdown (the log line can arrive late).
                    profiler = subprocess.Popen(
                        [asprof, "-d", str(max(1, args.duration - 3)), "-e", "cpu", "-i", "1ms", "-t",
                         "-o", "collapsed", "-f", out, match.group(1)],
                        stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
            time.sleep(0.2)
    if profiler is None:
        sys.exit(f"benchmark never started recording; see {log_path}")
    profiler.wait()
    if game.returncode != 0 or not os.path.isfile(out):
        sys.exit(f"profiling failed (game exit {game.returncode}); see {log_path}")
    print(f"      -> {os.path.relpath(out, ROOT)}\n")
    analyze(out, args.thread, args.top)


def clean_frame(frame):
    frame = re.sub(r"_\[[a-z0-9]\]$", "", frame)
    return frame.replace("/", ".")


def frame_group(frame):
    """Package (first three segments) for Java frames, library-ish name for native ones."""
    if "." in frame and not frame.startswith(("-[", "+[")) and " " not in frame and "::" not in frame:
        parts = frame.split(".")
        return ".".join(parts[:3]) if len(parts) > 3 else parts[0]
    return "native: " + frame.split("::")[0].split("(")[0][:48]


def thread_name(root):
    """'[Render thread tid=259]' -> 'Render thread'."""
    return re.sub(r"\s*tid=\d+\]$", "", root.lstrip("[")).rstrip("]") or root


def analyze(collapsed_file, thread, top, match=None):
    """Per-thread totals, then self and inclusive CPU time per package and method for one thread.

    thread="auto" picks the thread running Minecraft.runTick (the render thread is the process's main
    thread on macOS, which async-profiler may report under another name such as DestroyJavaVM)."""
    stacks = []
    per_thread, render_candidates = {}, {}
    with open(collapsed_file) as f:
        for line in f:
            stack, _, count = line.rstrip("\n").rpartition(" ")
            frames = stack.split(";")
            count = int(count)
            name = thread_name(frames[0])
            per_thread[name] = per_thread.get(name, 0) + count
            if "net/minecraft/client/Minecraft.runTick" in stack:
                render_candidates[name] = render_candidates.get(name, 0) + count
            stacks.append((name, [clean_frame(fr) for fr in frames[1:]], count))
    if thread == "auto":
        if not render_candidates:
            sys.exit("couldn't find the render thread (no Minecraft.runTick samples)")
        thread = max(render_candidates, key=render_candidates.get)

    total = sum(per_thread.values())
    print(f"threads ({total} samples, ~1 ms each, from {os.path.relpath(collapsed_file, ROOT)}):")
    for name, count in sorted(per_thread.items(), key=lambda kv: -kv[1])[:8]:
        print(f"    {count:7d}  {name}" + ("   <- analysed" if name == thread else ""))

    self_counts, total_counts, self_groups, total_groups = {}, {}, {}, {}
    n = 0
    for name, frames, count in stacks:
        if name != thread or not frames:
            continue
        n += count
        leaf = frames[-1]
        self_counts[leaf] = self_counts.get(leaf, 0) + count
        group = frame_group(leaf)
        self_groups[group] = self_groups.get(group, 0) + count
        for fr in set(frames):
            total_counts[fr] = total_counts.get(fr, 0) + count
        for group in {frame_group(fr) for fr in frames}:
            total_groups[group] = total_groups.get(group, 0) + count
    if not n:
        sys.exit(f"no samples for thread '{thread}'")

    print(f"\n{thread}: {n} samples")
    sections = (("inclusive by package", total_groups), ("self by package", self_groups),
                ("self by method", self_counts), ("inclusive by method", total_counts))
    if match:
        pattern = re.compile(match)
        sections = ((f"inclusive by method matching /{match}/", {k: v for k, v in total_counts.items() if pattern.search(k)}),)
    for title, counts in sections:
        print(f"\n  {title}:")
        for name, count in sorted(counts.items(), key=lambda kv: -kv[1])[:top]:
            print(f"    {100 * count / n:5.1f}%  {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    run_options = argparse.ArgumentParser(add_help=False)
    run_options.add_argument("--label", help="result label (default: current git commit)")
    run_options.add_argument("--runs", type=int, default=3)
    run_options.add_argument("--duration", type=int, default=30, help="recorded seconds (one camera revolution)")
    run_options.add_argument("--warmup", type=int, default=20, help="seconds of warmup before recording")
    run_options.add_argument("--render-distance", type=int, default=16)
    run_options.add_argument("--width", type=int, default=1600)
    run_options.add_argument("--height", type=int, default=900)
    run_options.add_argument("--cooldown", type=int, default=5, help="seconds to idle between runs")
    run_options.add_argument("--regenerate", action="store_true", help="rebuild the pre-generated world template first")

    sub.add_parser("run", parents=[run_options], help="run the benchmark N times")

    p_ab = sub.add_parser("ab", parents=[run_options],
                          help="interleaved runs of a baseline git ref vs the working tree, then compare")
    p_ab.add_argument("baseline", nargs="?", default="HEAD", help="git ref to compare against (default: HEAD)")

    p_profile = sub.add_parser("profile", parents=[run_options], help="one run with async-profiler attached, then a CPU breakdown")
    p_profile.add_argument("--thread", default="auto")
    p_profile.add_argument("--top", type=int, default=25)

    p_analyze = sub.add_parser("analyze", help="CPU breakdown of a .collapsed file from a profiled run")
    p_analyze.add_argument("file")
    p_analyze.add_argument("--thread", default="auto")
    p_analyze.add_argument("--top", type=int, default=25)
    p_analyze.add_argument("--match", help="only list methods whose name matches this regex (inclusive time)")

    p_show = sub.add_parser("show", help="summarise results")
    p_show.add_argument("specs", nargs="*", help="labels or result files (default: all labels)")

    p_cmp = sub.add_parser("compare", help="compare two labels or result files")
    p_cmp.add_argument("baseline")
    p_cmp.add_argument("candidate")

    args = parser.parse_args()
    if args.command == "run":
        run(args)
    elif args.command == "ab":
        ab(args)
    elif args.command == "profile":
        profile(args)
    elif args.command == "analyze":
        analyze(args.file, args.thread, args.top, args.match)
    elif args.command == "show":
        specs = args.specs or sorted({os.path.basename(p).rsplit("-", 2)[0] for p in glob.glob(os.path.join(RESULTS, "*.json"))})
        show(specs)
    else:
        compare(args.baseline, args.candidate)


if __name__ == "__main__":
    main()
