import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', compression=zipfile.ZIP_DEFLATED) as apk:
    apk.write(sys.argv[2], 'classes.dex')
    apk.write(sys.argv[3], 'lib/arm64-v8a/libp2pprobe.so')
