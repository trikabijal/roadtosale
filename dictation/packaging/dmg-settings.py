# dmgbuild settings for the Just Talk installer DMG. Deterministic — writes the window geometry +
# icon layout directly into the volume's .DS_Store (no live Finder AppleScript, which was resizing
# unreliably and leaving white space / duplicate windows). Invoked from package.sh:
#   DMG_APP="…/Just Talk.app" DMG_BG="…/dmg-background.png" \
#     dmgbuild -s packaging/dmg-settings.py "Just Talk" "out.dmg"
import os.path

app_path = os.environ.get("DMG_APP", "dist-prod/Just Talk.app")
app_name = os.path.basename(app_path)          # "Just Talk.app"

# --- Content -------------------------------------------------------------------
files = [app_path]
symlinks = {"Applications": "/Applications"}

# --- Appearance ----------------------------------------------------------------
format = "UDZO"                                 # compressed, read-only
background = os.environ.get("DMG_BG", "packaging/dmg-background.png")
icon_size = 116
text_size = 13

# Window: ((x, y), (width, height)) in points — matches the 540x380 background so it fills exactly.
window_rect = ((220, 140), (1000, 420))
default_view = "icon-view"
show_icon_preview = False
include_icon_view_settings = True
arrange_by = None
label_pos = "bottom"

# Icon centres (points from the window's top-left) — line up with the arrow baked into the background.
icon_locations = {
    app_name: (325, 250),
    "Applications": (565, 250),
}
