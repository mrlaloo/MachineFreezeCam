Machine Freeze Cam
==================
Purpose: live-camera visual tachometer / digital strobe for repeating machine motion.

How it works:
- Camera runs continuously in the background.
- The visible image is sampled at the selected FPM rate.
- When the sampling rate matches a repeating machine event, that event appears nearly stationary.

Controls:
- -10 / -1 / +1 / +10: fine tune FPM.
- 650 / 700 / 750 / 800: quick presets.
- START FREEZE: begin sampled camera view.
- HOLD FRAME: stop on one frame.

Important:
This is a visual diagnostic aid, not a calibrated safety instrument. Keep the phone and your body outside machine guarding and follow all plant safety/LOTO rules.

Build:
Open this folder in a current Android Studio, let Gradle sync, then Build > Build APK(s).
