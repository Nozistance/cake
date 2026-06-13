import sys, json, os, shutil, subprocess, urllib.request, time
import yt_dlp

def _log(*a):
    print('[cake.py]', *a, file=sys.stderr, flush=True)

_STALL = int(os.environ.get('DL_STALL_SECONDS', '90') or 0)

class _Stalled(Exception):
    pass

def _mk_hooks():
    state = {'bytes': -1, 'ts': time.monotonic()}
    def hook(d):
        st = d.get('status')
        if st == 'finished':
            state['bytes'], state['ts'] = -1, time.monotonic()
            return
        if st != 'downloading':
            return
        got = d.get('downloaded_bytes') or 0
        now = time.monotonic()
        if got != state['bytes']:
            state['bytes'], state['ts'] = got, now
        elif _STALL and now - state['ts'] > _STALL:
            raise _Stalled('no progress for %ds' % _STALL)
        tot = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
        print('PROGRESS %d %d' % (got, tot), file=sys.stderr, flush=True)
    return [hook]

def _saved(path):
    ap = os.path.abspath(os.path.expanduser(path))
    try:
        sz = os.path.getsize(ap)
    except OSError:
        sz = -1
    _log('saved', repr(path), '-> abspath', repr(ap),
         'exists', os.path.exists(ap), 'bytes', sz,
         'dir', repr(os.path.dirname(ap)),
         'dir_exists', os.path.isdir(os.path.dirname(ap)))
    return path

_BASE = {
    'noplaylist': True,
    'restrictfilenames': True,
    'merge_output_format': 'mp4',
    'retries': 5,
    'fragment_retries': 5,
    'socket_timeout': 30,
    'remote_components': ['ejs:github'],
    'extractor_args': {'youtube': {'skip': ['hls']}},
    'concurrent_fragment_downloads': 4,
    'http_chunk_size': 10485760,
    'quiet': True,
    'no_warnings': True,
    'noprogress': True,
    'ignoreerrors': False,
    'postprocessor_args': {'merger': ['-movflags', '+faststart']},
}

_cachedir = os.environ.get('YTDLP_CACHEDIR')
if _cachedir:
    _BASE['cachedir'] = _cachedir
if shutil.which('aria2c'):
    _BASE['external_downloader'] = {'default': 'aria2c'}
    _BASE['external_downloader_args'] = {'aria2c': ['-x16', '-s16', '-k1M', '--summary-interval=0',
                                                    '--timeout=30', '--connect-timeout=30',
                                                    '--max-tries=5', '--retry-wait=3']}

def _path(y, res):
    rd = res.get('requested_downloads')
    return rd[0]['filepath'] if rd else y.prepare_filename(res)

def _size(d):
    return d.get('filesize') or d.get('filesize_approx') or 0

def _thumb_url(info):
    for t in reversed(info.get('thumbnails') or []):
        u = t.get('url') or ''
        if u.endswith('.jpg') or '.jpg?' in u:
            return u
    return info.get('thumbnail')

def _thumb(info, base):
    src = _thumb_url(info)
    if not src:
        return None
    raw, out = base + '.thsrc', base + '.thumb.jpg'
    try:
        urllib.request.urlretrieve(src, raw)
        subprocess.run(['ffmpeg', '-y', '-i', raw, '-vf',
                        'scale=320:320:force_original_aspect_ratio=decrease', out],
                       check=True, capture_output=True)
        return out
    except Exception:
        return None
    finally:
        try:
            os.remove(raw)
        except OSError:
            pass

def download(url, fmt, outtmpl):
    opts = dict(_BASE, format=fmt, outtmpl=outtmpl, progress_hooks=_mk_hooks())
    with yt_dlp.YoutubeDL(opts) as y:
        return _saved(_path(y, y.extract_info(url, download=True)))

def info(url):
    with yt_dlp.YoutubeDL(dict(_BASE, skip_download=True)) as y:
        data = y.extract_info(url, download=False)
        fmts = data.get('formats') or []
        _log('info', repr(data.get('id')), 'title', repr(data.get('title')),
             'dur', data.get('duration'), 'formats', len(fmts),
             'heights', sorted({f.get('height') for f in fmts if f.get('height')}))
        return json.dumps(yt_dlp.YoutubeDL.sanitize_info(data))

def _incomplete(formats):
    vo = lambda f: (f.get('vcodec') or 'none') != 'none' and (f.get('acodec') or 'none') == 'none'
    ao = lambda f: (f.get('vcodec') or 'none') == 'none' and (f.get('acodec') or 'none') != 'none'
    return all(vo(f) for f in formats) or all(ao(f) for f in formats)

def sizes(info_json, selectors_json):
    out = {}
    info = json.loads(info_json)
    dur = info.get('duration') or 0
    formats = info.get('formats') or []
    ctx = {'formats': formats, 'incomplete_formats': _incomplete(formats)}
    ydl = yt_dlp.YoutubeDL(dict(_BASE, quiet=True, no_warnings=True))
    for k, fmt in json.loads(selectors_json).items():
        if k == 'audio':
            auds = [f for f in formats
                    if (f.get('acodec') or 'none') != 'none' and (f.get('vcodec') or 'none') == 'none']
            best = max(auds, key=lambda f: f.get('abr') or 0, default=None)
            if best:
                out[k] = _size(best) or int((best.get('abr') or 160) * 1000 / 8 * dur)
            else:
                out[k] = int(160000 / 8 * dur)
            continue
        try:
            sel = list(ydl.build_format_selector(fmt)(dict(ctx)))
            chosen = sel[0] if sel else None
            rf = chosen.get('requested_formats') if chosen else None
            out[k] = sum(_size(f) for f in rf) if rf else (_size(chosen) if chosen else 0)
        except Exception:
            out[k] = 0
    return json.dumps(out)

def _fallback_url(info):
    return info.get('webpage_url') or info.get('original_url')

def download_info(info_json, fmt, outtmpl):
    info = json.loads(info_json)
    opts = dict(_BASE, format=fmt, outtmpl=outtmpl, progress_hooks=_mk_hooks())
    try:
        with yt_dlp.YoutubeDL(opts) as y:
            return _saved(_path(y, y.process_ie_result(info, download=True)))
    except yt_dlp.utils.DownloadError:
        _log('download_info: process_ie_result failed, retrying via fallback url')
        with yt_dlp.YoutubeDL(opts) as y:
            return _saved(_path(y, y.extract_info(_fallback_url(info), download=True)))

def audio_info(info_json, outtmpl):
    info = json.loads(info_json)
    skip = ('merge_output_format', 'restrictfilenames')
    opts = {k: v for k, v in _BASE.items() if k not in skip}
    opts.update(format='bestaudio[ext=m4a]/bestaudio/best', outtmpl=outtmpl,
                progress_hooks=_mk_hooks(),
                postprocessors=[
                    {'key': 'FFmpegExtractAudio', 'preferredcodec': 'm4a', 'preferredquality': '0'},
                    {'key': 'FFmpegMetadata', 'add_metadata': True},
                ])
    try:
        with yt_dlp.YoutubeDL(opts) as y:
            path = _path(y, y.process_ie_result(info, download=True))
    except yt_dlp.utils.DownloadError:
        with yt_dlp.YoutubeDL(opts) as y:
            path = _path(y, y.extract_info(_fallback_url(info), download=True))
    _saved(path)
    thumb = _thumb(info, os.path.splitext(path)[0])
    return json.dumps({'audio': path, 'thumb': thumb})

_OPS = {
    'info':          lambda r: info(r['url']),
    'sizes':         lambda r: sizes(r['info'], r['selectors']),
    'download':      lambda r: download(r['url'], r['fmt'], r['outtmpl']),
    'download_info': lambda r: download_info(r['info'], r['fmt'], r['outtmpl']),
    'audio_info':    lambda r: audio_info(r['info'], r['outtmpl']),
}

def main():
    req = json.load(sys.stdin)
    op = _OPS.get(req.get('cmd'))
    if op is None:
        print('unknown cmd: %r' % req.get('cmd'), file=sys.stderr)
        sys.exit(2)
    out = op(req)
    sys.stdout.write(out)
    sys.stdout.flush()

if __name__ == '__main__':
    main()
