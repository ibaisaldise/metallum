#!/usr/bin/env python3
"""Run and compare Metallum benchmarks.

  scripts/bench.py run  [--label L] [--runs N] [--duration S] [--warmup S] [--render-distance N]
  scripts/bench.py show [LABEL|FILE ...]
  scripts/bench.py compare BASELINE CANDIDATE

`run` launches `./gradlew runBenchmark` N times (a fixed-seed world, camera spin, then quit) and prints
the median of the runs. The first time, it generates the world in a longer preparation run and keeps it
as a template (run/metallum-bench/templates/); each measured run starts from a fresh copy of it. Labels default to the current git commit. BASELINE/CANDIDATE are labels (all
run/metallum-bench/<label>-*.json files, medianed) or paths to individual result files.
"""
import argparse
import glob
import json
import os
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


def launch(env, label, args, warmup, duration):
    """One game launch. Returns the result file path, or exits on failure."""
    cmd = [os.path.join(ROOT, "gradlew"), "runBenchmark", "--console=plain", "-q",
           f"-Pbench.label={label}", f"-Pbench.duration={duration}", f"-Pbench.warmup={warmup}",
           f"-Pbench.renderDistance={args.render_distance}", f"-Pbench.width={args.width}", f"-Pbench.height={args.height}"]
    os.makedirs(RESULTS, exist_ok=True)
    log_path = os.path.join(RESULTS, f"{label}.log")
    started = time.time()
    with open(log_path, "w") as log:
        code = subprocess.call(cmd, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    new = [p for p in glob.glob(os.path.join(RESULTS, f"{label}-[0-9]*-[0-9]*.json")) if os.path.getmtime(p) >= started]
    if code != 0 or not new:
        sys.exit(f"benchmark run failed (exit {code}); see {log_path}")
    return max(new, key=os.path.getmtime)


def restore_world(template):
    shutil.rmtree(WORLD, ignore_errors=True)
    shutil.copytree(template, WORLD)


def prepare_template(env, args, template):
    """Generate the world once, with a long warmup so every chunk in render distance exists, and snapshot it."""
    print(f"preparing world template for render distance {args.render_distance} (~{PREPARE_WARMUP + 35}s)...", flush=True)
    shutil.rmtree(WORLD, ignore_errors=True)
    os.remove(launch(env, "_prepare", args, PREPARE_WARMUP, 5))
    shutil.rmtree(template, ignore_errors=True)
    shutil.copytree(WORLD, template)
    print(f"      -> {os.path.relpath(template, ROOT)} ({count_chunks(template)} chunks)")


def run(args):
    label = args.label or current_commit()
    env = dict(os.environ)
    home = java_home()
    if home:
        env["JAVA_HOME"] = home

    # Every measured run starts from an identical copy of a fully generated world, so no run pays for
    # terrain generation and none inherits state (time, entities, player) saved by the previous one.
    template = os.path.join(TEMPLATES, f"world-rd{args.render_distance}")
    if args.regenerate or not os.path.isdir(template):
        prepare_template(env, args, template)
    template_chunks = count_chunks(template)

    for i in range(args.runs):
        print(f"[{i + 1}/{args.runs}] running benchmark '{label}' (~{args.warmup + args.duration + 25}s)...", flush=True)
        restore_world(template)
        result = launch(env, label, args, args.warmup, args.duration)
        print(f"      -> {os.path.relpath(result, ROOT)}")
        generated = count_chunks(WORLD) - template_chunks
        if generated > 0:
            print(f"      WARNING: {generated} new chunks were generated during this run; "
                  f"regenerate the template with --regenerate", file=sys.stderr)
        if i + 1 < args.runs and args.cooldown:
            time.sleep(args.cooldown)
    print()
    show([label])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p_run = sub.add_parser("run", help="run the benchmark N times")
    p_run.add_argument("--label", help="result label (default: current git commit)")
    p_run.add_argument("--runs", type=int, default=3)
    p_run.add_argument("--duration", type=int, default=30, help="recorded seconds (one camera revolution)")
    p_run.add_argument("--warmup", type=int, default=20, help="seconds of warmup before recording")
    p_run.add_argument("--render-distance", type=int, default=16)
    p_run.add_argument("--width", type=int, default=1600)
    p_run.add_argument("--height", type=int, default=900)
    p_run.add_argument("--cooldown", type=int, default=5, help="seconds to idle between runs")
    p_run.add_argument("--regenerate", action="store_true", help="rebuild the pre-generated world template first")

    p_show = sub.add_parser("show", help="summarise results")
    p_show.add_argument("specs", nargs="*", help="labels or result files (default: all labels)")

    p_cmp = sub.add_parser("compare", help="compare two labels or result files")
    p_cmp.add_argument("baseline")
    p_cmp.add_argument("candidate")

    args = parser.parse_args()
    if args.command == "run":
        run(args)
    elif args.command == "show":
        specs = args.specs or sorted({os.path.basename(p).rsplit("-", 2)[0] for p in glob.glob(os.path.join(RESULTS, "*.json"))})
        show(specs)
    else:
        compare(args.baseline, args.candidate)


if __name__ == "__main__":
    main()
