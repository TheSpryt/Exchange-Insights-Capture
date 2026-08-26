# Exchange Insights Capture

A ShadowPlay-style instant replay plugin for [RuneLite](https://runelite.net/).

Exchange Insights Capture continuously keeps the last few seconds of gameplay in a rolling
in-memory buffer and automatically saves an MP4 clip of the moments leading up
to (and just after) configurable in-game events — deaths, collection log
unlocks, level ups, valuable drops, pets and more. You can also bind a hotkey to
save a clip on demand.

## How it works

- Frames are sampled from the client at your chosen framerate via RuneLite's
  `DrawManager` and held in a rolling buffer as JPEG-compressed bytes, so memory
  stays bounded even with several seconds retained.
- When a trigger fires, the buffered lead-up is combined with a short
  post-event tail and encoded to an `.mp4` on a background thread. The H.264
  encoder and the MP4 container are both part of this plugin, so it has **no
  third-party dependencies, no native binaries and no external processes**.

## Configuration

**Recording**
- **Clip length** — total clip duration (default 15s).
- **Post-event padding** — seconds recorded after the event; the remainder is
  the lead-up (default 2s).
- **Framerate** — frames per second to capture (default 15). **This is the main
  performance dial** — see [Performance](#performance) below.
- Clips are recorded at the client's own resolution. Resizing the client
  discards the buffered frames and refills at the new size, because one clip
  cannot mix two frame shapes.
- **JPEG buffer quality** — trade memory use against clip quality.
- **Draw cursor** — overlay a marker at the mouse position (the OS cursor is
  not part of captured frames, so it is drawn by the plugin).

**Triggers**
- Manual save hotkey, on death, on collection log unlock, on level up, on
  valuable drop (with a configurable gp threshold), on pet, on quest
  completion, on combat task.

**Output**
- **Save folder** — defaults to the RuneLite directory's `captures` folder,
  alongside RuneLite's own `screenshots`.
- **Chat message on save** — confirms each saved clip in-game.
- **Show status overlay** — a small on-screen indicator showing when the plugin
  is armed, actively recording a clip, or has just saved one.

## Usage

1. Enable **Exchange Insights Capture** in the RuneLite plugin list.
2. Open the plugin's config panel and pick which events should save a clip
   (and, optionally, set a **Manual save hotkey** for on-demand capture).
3. Play normally. When a trigger fires, the plugin captures the surrounding
   seconds and writes an `.mp4` to your save folder. The status overlay flashes
   green and — if enabled — a chat message confirms the save.

Clips are named `<timestamp>_<reason>.mp4` (for example
`2026-06-23_18-30-05_death.mp4`), so they sort chronologically and are easy to
find after a session. They are written to `.runelite/captures/`.

## Performance

Capturing a frame means asking the client to hand back what it just rendered.
On the **GPU** and **117HD** renderers that requires reading pixels back from
the GPU, which stalls the render pipeline — so capture rate has a direct and
sometimes large cost in in-game FPS.

**Framerate is the dial that matters.** A frame nobody asks for costs nothing,
so the total is linear in this setting. Measured on a machine that runs the game
comfortably: 60 is affordable, and 120 takes around 80fps off the game. If your
FPS drops, lower this before anything else.

The cost also scales with display scaling, not just canvas size. On a monitor at
150% the readback is more than twice the size for the same window.

Secondary levers:

- **Clip length** sets how many frames are held in memory at once.
- **Quality** trades clip size and encoding time against how the clip looks.

The plugin keeps at most one frame request outstanding at a time, so it will not
pile up requests and make a struggling client worse.

## Changelog

### Unreleased
- Status overlay showing armed / recording / saved state, with a brief flash
  when a clip is written (toggle under **Output**).
- Optional cursor marker drawn into saved clips.

### 1.0.0
- Initial release: rolling in-memory buffer with pure-Java H.264/MP4 encoding,
  automatic triggers (death, collection log, level up, valuable drop, pet,
  quest, combat task) and a manual save hotkey.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
