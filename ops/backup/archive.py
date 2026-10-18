"""Bounded archive transport for an authenticated, trusted, isolated backup.

No extractall, links, special files, traversal, duplicate entries or overwrite.
Also runs in a networkless helper against one exact owned stopped Rabbit volume.
"""
from pathlib import Path, PurePosixPath
import os
import sys
import tarfile

MAX_BYTES = 1024 * 1024 * 1024
MAX_FILES = 20000


def members(archive):
    result, seen, size = [], set(), 0
    for item in archive:
        name = PurePosixPath(item.name)
        if (not item.name or item.name.startswith('/') or '\\' in item.name or ':' in item.name or
                any(part in ('', '.', '..') for part in item.name.split('/')) or
                item.name in seen or not (item.isfile() or item.isdir()) or
                item.size < 0 or item.uid < 0 or item.gid < 0 or item.uid > 65535 or item.gid > 65535):
            raise ValueError('unsafe archive entry')
        seen.add(item.name)
        size += item.size
        result.append((item, name))
        if len(result) > MAX_FILES or size > MAX_BYTES:
            raise ValueError('archive exceeds declared bounds')
    # A file cannot be an ancestor; validate everything before the first write.
    files = {name for item, name in result if item.isfile()}
    if any(any(parent in files for parent in name.parents) for _, name in result):
        raise ValueError('archive file/directory conflict')
    return result


def pack(source, destination):
    source, destination = Path(source), Path(destination)
    if destination.exists() or source.is_symlink() or not source.is_dir():
        raise ValueError('refusing archive source or overwrite')
    paths = sorted(source.rglob('*'))
    if any(p.is_symlink() or not (p.is_file() or p.is_dir()) for p in paths):
        raise ValueError('unsupported archive source')
    if len(paths) > MAX_FILES or sum(p.stat().st_size for p in paths if p.is_file()) > MAX_BYTES:
        raise ValueError('archive source exceeds bounds')
    with tarfile.open(destination, 'x') as archive:
        for path in paths:
            archive.add(path, arcname=path.relative_to(source).as_posix(), recursive=False)


def unpack(source, destination, ownership=False):
    destination = Path(destination)
    if destination.is_symlink() or not destination.is_dir() or any(destination.iterdir()):
        raise ValueError('restore destination must be empty')
    with tarfile.open(source, 'r:') as archive:
        entries = members(archive)
        for item, name in entries:
            target = destination.joinpath(*name.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            if item.isdir():
                target.mkdir(exist_ok=True)
            else:
                with archive.extractfile(item) as stream, target.open('xb') as output:
                    while chunk := stream.read(1024 * 1024):
                        output.write(chunk)
                target.chmod(item.mode & 0o777)
        # Apply directory permissions after children; no setuid/sticky restoration.
        for item, name in reversed(entries):
            target = destination.joinpath(*name.parts)
            if item.isdir():
                target.chmod(item.mode & 0o777)
            if ownership:
                os.chown(target, item.uid, item.gid, follow_symlinks=False)


if __name__ == '__main__':
    try:
        if sys.argv[1] == 'pack':
            pack('/state', '/transport/broker.tar')
        elif sys.argv[1] == 'unpack':
            unpack('/transport/broker.tar', '/state', ownership=True)
        else:
            raise ValueError('unknown operation')
    except Exception:
        print('FAIL: owned bounded archive rejected; diagnostics withheld')
        sys.exit(1)
