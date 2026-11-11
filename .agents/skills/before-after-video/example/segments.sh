# Segments of the ui-brand-prototype before/after video (v0.3.8 against the restyle).
# runner.sh sources this file and runs seg_<name> once for each app. Helpers come from runner.sh.

seg_welcome() {
  fresh_start
  sleep 0.5
  still 4
}

seg_server() {
  fresh_start
  checkpoint typography
  rec_start
  sleep 1
  connect_lab
  sleep 1
  rec_stop
}

seg_story() {
  launch
  tapt "Open sessions"
  sleep 1.5
  tapt "Settings" exact
  sleep 1.5
  tapt "Developer" exact
  sleep 1.5
  tapt "Playgrounds" exact
  sleep 1.5
  if ! has "Show Welcome"; then
    tapt "Welcome Preview"
    sleep 1
  fi
  rec_start
  sleep 0.8
  tapt "Show Welcome"
  sleep 7.5
  rec_stop
  tapt "Close" exact
}

seg_transcript() {
  checkpoint typography
  open_lab_session
  # Finds the table's row before recording, to swipe it sideways.
  local xy y
  xy=$(where "Tool schemas")
  y=${xy#* }
  rec_start
  sleep 1.5
  swipe 950 "$y" 250 "$y" 700
  sleep 1.8
  swipe 250 "$y" 950 "$y" 700
  sleep 1
  local i
  for i in 1 2; do
    swipe 540 800 540 1800 900
    sleep 1.6
  done
  rec_stop
}

seg_tools() {
  checkpoint tool-mix-failed
  open_lab_session
  rec_start
  sleep 1.5
  tapt "Used 19 tools"
  sleep 2.5
  tapt "swift test"
  sleep 3
  rec_stop
}

seg_composer() {
  checkpoint intro
  open_lab_session
  rec_start
  sleep 1.2
  tapt "Message" exact
  sleep 1.2
  typew "Can you tighten the spacing in the composer?"
  sleep 1.2
  tapt "Send message"
  sleep 2.5
  tapt "Add attachment"
  sleep 2.5
  rec_stop
  back
  back
}

seg_model() {
  checkpoint intro
  open_lab_session
  rec_start
  sleep 1
  tapt "Message" exact
  sleep 1.5
  tapt "Choose model"
  sleep 2
  tapt "Search"
  sleep 0.8
  typew "pro"
  sleep 2
  rec_stop
  back
  back
}

seg_drawer() {
  checkpoint complete
  lab /lab/other-session '{"action":"long-titles"}'
  lab /lab/other-session '{"action":"many","count":12}'
  launch
  rec_start
  sleep 1
  tapt "Open sessions"
  sleep 3.5
  rec_stop
}

seg_settings() {
  launch
  tapt "Open sessions"
  sleep 1.5
  rec_start
  sleep 0.8
  tapt "Settings" exact
  sleep 2.2
  swipe 540 1500 540 900 700
  sleep 1.8
  swipe 540 900 540 1600 700
  sleep 1.2
  tapt "Experimental" exact
  sleep 2.2
  tapt "One Picker"
  sleep 1
  tapt "Agent Selection"
  sleep 1.8
  rec_stop
}

seg_prompts() {
  checkpoint permission
  open_lab_session
  rec_start
  sleep 3.5
  checkpoint question
  sleep 4
  rec_stop
}

seg_voice() {
  checkpoint complete
  a shell setprop debug.opencode.voice_demo 1
  open_lab_session
  rec_start
  sleep 1
  tapt "Start voice call"
  sleep 2.5
  tapt "Voice call" exact
  sleep 6
  rec_stop
  # The scripted call keeps the main thread busy on the software-rendered emulator, so a tap on End
  # can time out into an "isn't responding" dialog. Stop the app instead.
  a shell am force-stop "$PKG"
  a shell setprop debug.opencode.voice_demo 0
}

seg_dark() {
  theme yes
  checkpoint typography
  open_lab_session
  rec_start
  sleep 2
  tapt "Open sessions"
  sleep 2.2
  tapt "Settings" exact
  sleep 2.2
  rec_stop
  theme no
}
