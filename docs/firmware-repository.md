# The firmware repository (`sensorfirmwarebinary`)

The updater reads its list of installable firmware from a plain git repository
served over `raw.githubusercontent.com`. There is no releases API, no
authentication and no build step: **publishing new firmware is a commit**.

## Layout

```
sensorfirmwarebinary/
├── manifest.json              <- the index the updater fetches
├── firmware/
│   ├── soundnet_firmware-2.0.7-SOUNDNET_V1_R6.hex
│   ├── soundnet_firmware-2.0.7-SOUNDNET_V1_R5.hex
│   └── soundnet_firmware-2.0.6-SOUNDNET_V1_R6.hex
└── README.md
```

Two rules, and they are the whole contract:

1. `manifest.json` lives at the repository root.
2. Every `file` it names lives in `firmware/`.

Old firmware is never deleted. Researchers sometimes need to put a sensor back
onto the version the rest of a deployment is running, and a `.hex` is tens of
kilobytes — there is no reason to prune.

## `manifest.json`

```json
{
  "schemaVersion": 1,
  "updated": "2026-08-31",
  "firmware": [
    {
      "revision": "SOUNDNET_V1_R6",
      "version": "2.0.7",
      "file": "soundnet_firmware-2.0.7-SOUNDNET_V1_R6.hex",
      "sha256": "9f2c…",
      "released": "2026-08-31",
      "notes": "Fixes the random field lockups that needed a power cycle.",
      "recommended": true
    }
  ]
}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `revision` | yes | A `SENSOR_TYPE` name from `xsensmessage.h`. Unknown names are skipped with a warning. |
| `version` | yes | The `FIRMWARE_VERSION` string the sensor reports over serial. |
| `file` | yes | Bare file name inside `firmware/`. Any path is stripped. |
| `sha256` | strongly recommended | Checked after download. A mismatch is refused, not flashed. |
| `released` | no | Shown under the drop-down. |
| `notes` | no | One line, shown under the drop-down. Write it for a researcher, not a developer. |
| `recommended` | no | Sorts to the top of the list. Mark the newest build of each revision. |

`revision` and `version` together identify a build. Publishing the same pair
twice replaces the earlier one in the list.

### Why `sha256` matters

These bytes get written into a sensor's flash. A truncated download or a
corrupted cache file would produce a device that does not run, in a place where
nobody can re-flash it. The updater refuses anything whose hash does not match
and says so plainly.

An entry with no `sha256` still works, but the updater logs a warning.

### `schemaVersion`

Currently `1`. An updater that sees a higher number still installs every entry
it can parse, and tells the user its application may be out of date. Only raise
it for a genuinely incompatible change.

## Publishing

From the firmware source repository:

```bash
tools/build-firmware.sh --out ../sensorfirmwarebinary SOUNDNET_V1_R5 SOUNDNET_V1_R6
```

That compiles once per hardware revision, writes the `.hex` files, computes the
hashes, and merges them into `manifest.json` — keeping older entries whose files
are still present and marking the newest build of each revision as recommended.
Then:

```bash
cd ../sensorfirmwarebinary && git add -A && git commit -m "Firmware 2.0.7" && git push
```

Every updater already installed picks it up the next time it starts.

## Refreshing the offline fallback

The updater ships with a copy of the firmware so it works with no connection.
That copy only changes when the application is rebuilt, so refresh it whenever
you cut a release of the updater:

```bash
tools/build-firmware.sh \
  --out    ../sensorfirmwarebinary \
  --bundle ../sensorfirmwareupdater/src/main/resources/firmware
```

Keep the bundled set small — the two or three revisions most likely to be in
someone's hands. The repository is where the full history lives.

## Pointing somewhere else

The default is
`https://raw.githubusercontent.com/Sound-Net/sensorfirmwarebinary/main/`.
Override it for testing, or for an institution that mirrors internally:

```bash
java -Dsoundnet.firmware.repository=https://example.org/firmware/ -jar …
```

The URL is treated as a base; the updater appends `manifest.json` and
`firmware/<file>`.
