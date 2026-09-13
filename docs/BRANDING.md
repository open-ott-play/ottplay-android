# Android icon

The launcher icon uses the original synthetic test video bundled in this repository,
`app/src/main/res/raw/demo.mp4`. The background is the center square of the frame
at 1.8 seconds. It has no dependency on another repository or a hosted video.

Regenerate the background from the repository root with FFmpeg:

```sh
ffmpeg -hide_banner -loglevel error -y -ss 1.8 \
  -i app/src/main/res/raw/demo.mp4 \
  -vf 'crop=360:360:140:0' -frames:v 1 -update 1 \
  app/src/main/res/drawable-nodpi/ic_launcher_background.png
```

`drawable/ic_launcher.xml` layers this square frame underneath
`drawable/ic_launcher_foreground.xml`. The foreground keeps the white Play mark
and adds a 40% black scrim so it remains legible over the bright test pattern.
The source frame is preserved without a baked-in scrim or Play mark, allowing
other branding assets to reuse it.
