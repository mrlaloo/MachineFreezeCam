Machine Freeze Tach for Android
===============================
Purpose: a live-camera visual tachometer / digital strobe for repeating machine motion.

Version 2 direction:
- Android-first implementation inspired by the behavior of dedicated iOS video tachometer apps.
- Camera frames are sampled at the selected RPM rate so repeating motion can appear stationary.
- Large RPM readout with Hz and milliseconds per revolution.
- Fine and coarse controls: +/-1, +/-10, +/-100 RPM.
- Harmonic controls: divide-by-2 and multiply-by-2.
- Machine presets: 650, 700, 750, 800 RPM.
- HOLD freezes the current visible frame.
- LIGHT uses the rear camera torch when supported.
- SHARP requests a short exposure to reduce motion blur when Camera2 manual controls are available.
- The app measures incoming camera FPS and warns when the requested strobe frequency is above the sampling capability of the camera.

How to use:
1. Aim at one repeating feature on the moving machine component.
2. Start near the expected RPM.
3. Adjust until the selected feature appears stationary or drifts very slowly.
4. Use x2 / divide-by-2 to check for harmonic aliases.
5. Use SHARP in adequate light for clearer edges.

Important:
This is a visual diagnostic aid, not a calibrated safety instrument. Keep the phone and your body outside machine guarding and follow all plant safety/LOTO rules.

Build:
Open in a current Android Studio, let Gradle sync, then build/install the debug APK.
