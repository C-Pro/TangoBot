# TangoBot
=========

TangoBot is my experiment with Google [Project Tango Dev Kit](https://get.google.com/tango/).
It uses controls arduino-based robot via [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library and
[Firebase](https://firebase.google.com) realtime database to do some kind of (slow) remote control.

Tango sensors are used to get point cloud data which is used to determine if there is an obstacle in front of the robot.
If there is one, it turns (to the left) and continues its movement.

