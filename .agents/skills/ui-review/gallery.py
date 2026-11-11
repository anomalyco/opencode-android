#!/usr/bin/env python3
"""Before/after gallery for UI review screenshots and clips.

Each workstream writes into its own subfolder of the gallery folder:
    NN-slug-light-before.png / NN-slug-light-after.png   (or .mp4 for animations)
    NN-slug-dark-before.png  / NN-slug-dark-after.png
    NN-slug-light.txt        first line: title; other lines: what changed and the verdict
Files whose slug differs only by the NN- number and the -light / -dark ending are shown in one
row: light before, light after, dark before, dark after. A slug with no -light or -dark counts as
light. Newest items come first, and the page reloads itself when files change.

With --repo and --branch, an "after" older than the branch's last update (the last merge into the
combined branch) gets a red "stale" badge, because it doesn't show everyone's latest work.

Run:
    python3 gallery.py <folder> [--port 8766] [--title T] [--before-label "v0.3.10 release"]
                       [--after-label New] [--repo <checkout> --branch <combined branch>]
then open http://127.0.0.1:<port>/
"""
import argparse
import html
import http.server
import os
import re
import subprocess

MEDIA = (".png", ".jpg", ".jpeg", ".webp", ".mp4", ".webm")
THEMES = ("light", "dark")

args = None


def branch_updated_at():
    """Unix time the combined branch last moved, or 0 when stale badges are off."""
    if not args.repo or not args.branch:
        return 0.0
    result = subprocess.run(
        ["git", "-C", args.repo, "reflog", "show", "-1", "--date=unix", "--format=%gd", args.branch],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0 or "@{" not in result.stdout:
        return 0.0
    return float(result.stdout.strip().split("@{")[1].rstrip("}"))


def newest_change():
    newest = 0.0
    for folder, _, files in os.walk(args.folder):
        for name in files:
            mtime = os.path.getmtime(os.path.join(folder, name))
            if mtime > newest:
                newest = mtime
    return str(newest)


def media_tag(path):
    url = html.escape(path)
    if path.endswith((".mp4", ".webm")):
        return f'<video src="/{url}" controls autoplay muted loop playsinline></video>'
    return f'<a href="/{url}" target="_blank"><img src="/{url}" loading="lazy"></a>'


def split_slug(slug):
    """Turns "03-orb-expanded-dark" into ("orb-expanded", "dark")."""
    name = re.sub(r"^\d+[a-z]?-", "", slug)
    for theme in THEMES:
        if name.endswith("-" + theme):
            return name[: -len(theme) - 1], theme
    return name, "light"


def collect_groups(stream):
    """Groups a workstream's files: {name: {"mtime", "light": {...}, "dark": {...}}}."""
    groups = {}
    folder = os.path.join(args.folder, stream)
    for file_name in os.listdir(folder):
        base, ext = os.path.splitext(file_name)
        if ext == ".txt":
            slug, role = base, "text"
        elif ext in MEDIA and base.endswith("-before"):
            slug, role = base[: -len("-before")], "before"
        elif ext in MEDIA and base.endswith("-after"):
            slug, role = base[: -len("-after")], "after"
        else:
            continue
        name, theme = split_slug(slug)
        group = groups.setdefault(name, {"mtime": 0.0, "light": {}, "dark": {}})
        group[theme][role] = f"{stream}/{file_name}"
        mtime = os.path.getmtime(os.path.join(folder, file_name))
        if mtime > group["mtime"]:
            group["mtime"] = mtime
    return groups


def read_caption(path):
    lines = []
    with open(os.path.join(args.folder, path), encoding="utf-8") as file:
        for line in file.read().strip().splitlines():
            if line.strip():
                lines.append(line.strip())
    return lines


def render_figure(files, role, label, fresh_time):
    if role in files:
        media = media_tag(files[role])
        path = os.path.join(args.folder, files[role])
        if role == "after" and os.path.getmtime(path) < fresh_time:
            label += ' <span class="stale">stale: retake from the combined build</span>'
    else:
        media = '<div class="waiting">Not captured yet</div>'
    return f"<figure><figcaption>{label}</figcaption>{media}</figure>"


def render_group(name, group, fresh_time):
    title = name
    details = []
    for theme in THEMES:
        if "text" not in group[theme]:
            continue
        lines = read_caption(group[theme]["text"])
        if not lines:
            continue
        if title == name:
            # Drop a trailing "(light)" or ", dark mode" so one title covers both themes.
            title = re.sub(
                r"(\s*\((light|dark)( theme| mode)?\)|\s*[,–-]\s*(light|dark)( theme| mode)?)$",
                "",
                lines[0],
                flags=re.IGNORECASE,
            )
        for line in lines[1:]:
            if line not in details:
                details.append(line)

    themes_html = ""
    for theme in THEMES:
        files = group[theme]
        figures = render_figure(files, "before", html.escape(args.before_label), 0.0)
        figures += render_figure(files, "after", html.escape(args.after_label), fresh_time)
        themes_html += (
            f'<div class="theme {theme}"><div class="theme-label">{theme.title()}</div>'
            f'<div class="pair">{figures}</div></div>'
        )

    details_html = "<br>".join(html.escape(line) for line in details)
    return (
        f"<article><h3>{html.escape(title)}</h3><p>{details_html}</p>"
        f'<div class="themes">{themes_html}</div></article>'
    )


def render_page():
    streams = []
    for name in os.listdir(args.folder):
        if os.path.isdir(os.path.join(args.folder, name)) and not name.startswith("."):
            groups = collect_groups(name)
            if groups:
                newest = max(group["mtime"] for group in groups.values())
                streams.append((newest, name, groups))
    streams.sort(reverse=True)

    fresh_time = branch_updated_at()
    body = ""
    for _, stream, groups in streams:
        body += f"<section><h2>{html.escape(stream)}</h2>"
        ordered = sorted(groups.items(), key=lambda pair: pair[1]["mtime"], reverse=True)
        for name, group in ordered:
            body += render_group(name, group, fresh_time)
        body += "</section>"
    if not body:
        body = "<p>No screenshots yet. This page reloads when they arrive.</p>"

    title = html.escape(args.title)
    return f"""<!doctype html><html><head><meta charset="utf-8">
<title>{title}</title>
<style>
  body {{ font: 15px/1.45 Inter, system-ui, sans-serif; margin: 24px; background: #f6f6f6; color: #1b1b1b; }}
  h1 {{ font-size: 22px; margin: 0 0 4px; }}
  h2 {{ font-size: 18px; margin: 32px 0 8px; border-bottom: 1px solid #ccc; padding-bottom: 4px; }}
  h3 {{ font-size: 15px; margin: 0 0 4px; }}
  article {{ background: #fff; border: 1px solid #ddd; border-radius: 8px; padding: 14px; margin: 12px 0; }}
  article p {{ margin: 0 0 10px; color: #555; }}
  .themes {{ display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }}
  @media (max-width: 1100px) {{ .themes {{ grid-template-columns: 1fr; }} }}
  .theme {{ border-radius: 8px; padding: 10px; }}
  .theme.light {{ background: #ececec; }}
  .theme.dark {{ background: #262626; }}
  .theme-label {{ font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: .05em; margin-bottom: 6px; }}
  .theme.light .theme-label, .theme.light figcaption {{ color: #666; }}
  .theme.dark .theme-label, .theme.dark figcaption {{ color: #aaa; }}
  .pair {{ display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }}
  figure {{ margin: 0; min-width: 0; }}
  figcaption {{ font-size: 12px; margin-bottom: 4px; }}
  .stale {{ background: #b3261e; color: #fff; border-radius: 4px; padding: 1px 6px; margin-left: 6px; }}
  img, video {{ width: 100%; max-width: 380px; border-radius: 6px; display: block; }}
  .waiting {{ padding: 3em 1em; border: 1px dashed #888; border-radius: 6px; text-align: center; color: #888; }}
</style></head><body>
<h1>{title}</h1>
<div style="color:#777">Newest first. Updates automatically. Click a screenshot to open it full size.</div>
{body}
<script>
  let stamp = null;
  setInterval(async () => {{
    const now = await (await fetch('/stamp')).text();
    if (stamp !== null && now !== stamp) location.reload();
    stamp = now;
  }}, 5000);
</script>
</body></html>"""


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *handler_args, **kwargs):
        super().__init__(*handler_args, directory=args.folder, **kwargs)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            self.reply(render_page(), "text/html; charset=utf-8")
        elif self.path == "/stamp":
            self.reply(newest_change(), "text/plain")
        else:
            super().do_GET()

    def reply(self, text, content_type):
        data = text.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *log_args):
        pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Before/after UI review gallery")
    parser.add_argument("folder", help="gallery folder; each subfolder is one workstream")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--title", default="OpenCode Android: UI review")
    parser.add_argument("--before-label", default="Release")
    parser.add_argument("--after-label", default="New")
    parser.add_argument("--repo", help="checkout holding the combined branch (for stale badges)")
    parser.add_argument("--branch", help="combined branch every after must be taken from")
    args = parser.parse_args()
    args.folder = os.path.abspath(args.folder)
    http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
