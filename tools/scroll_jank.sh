#!/bin/sh
# Quick jank check on any phone, no build needed: flings Album's Photos grid with adb input, then drags the fast
# scroller through the whole timeline, and prints Android's own frame stats (dumpsys gfxinfo) for each.
# Open Album on Photos (scrolled to the top) first. A frame is janky when it misses the display's deadline,
# so at 144 Hz the budget is 6.9 ms. Unlike the Macrobenchmark this leaves accessibility off, like a normal phone.
set -eu
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB=adb
PKG=com.aditya.album
size=$($ADB shell wm size | tail -1 | sed 's/.*: //') # 1080x2400; an override (wm size WxH) wins
w=${size%x*}
h=${size#*x}
x=$((w / 2))

stats() {
    $ADB shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky frames|percentile|Number Frame deadline missed" | sed 's/^ */  /'
}

echo "Fling down 3 times, then up 3 times:"
$ADB shell dumpsys gfxinfo $PKG reset > /dev/null
for _ in 1 2 3; do $ADB shell input swipe $x $((h * 7 / 10)) $x $((h * 3 / 10)) 50; sleep 2; done
for _ in 1 2 3; do $ADB shell input swipe $x $((h * 3 / 10)) $x $((h * 7 / 10)) 50; sleep 2; done
stats

# The thumb only shows while the grid scrolls: hold a short drag on the grid while UiAutomator finds it. Sets tx ty.
thumb() {
    $ADB shell input motionevent DOWN $x $((h / 2))
    $ADB shell input motionevent MOVE $x $((h / 2 + ${1:-0}))
    bounds=$($ADB shell "uiautomator dump /sdcard/album-ui.xml > /dev/null && grep -o 'resource-id=\"fast_scroller\"[^>]*' /sdcard/album-ui.xml" | grep -o 'bounds="[^"]*"' | tr -c '0-9' ' ')
    $ADB shell input motionevent UP $x $((h / 2 + ${1:-0}))
    set -- $bounds
    [ $# -eq 4 ] || { echo "No fast scroller found: is Album open on Photos with a long timeline?"; exit 1; }
    tx=$((($1 + $3) / 2))
    ty=$((($2 + $4) / 2))
}

echo "Fast scroller, top to bottom and back, 1.5 s each way:"
thumb -200
$ADB shell dumpsys gfxinfo $PKG reset > /dev/null
$ADB shell input swipe $tx $ty $tx $((h * 9 / 10)) 1500
thumb 200
$ADB shell input swipe $tx $ty $tx $((h / 10)) 1500
sleep 1
stats
