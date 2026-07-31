# FreeWidget

LSPosed module for MIUI Home — free widget placement and per-orientation position memory for 4×2 widgets.

## Features
- Free placement of widgets within current grid bounds
- Per-orientation position memory (landscape 6×4 / portrait 4×6)
- Automatic position restoration after screen rotation
- OOB crash protection
- Unrestricted widget resize frame bounds (Pad + Phone)

## Requirements
- LSPosed (API 82)
- MIUI Home (com.miui.home)
- Root access not required

## Config
Create `/data/local/tmp/betterdock_config.json`:
```json
{
  "free_widget": true,
  "map_4x2": true
}
```
