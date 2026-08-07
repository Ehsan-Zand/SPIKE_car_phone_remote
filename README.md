# SPIKE Car Remote

Android 11 Kotlin app for controlling a LEGO SPIKE Prime hub running Pybricks 4.x.

## BLE protocol

The app connects to the Pybricks GATT service:

Service:
c5f50001-8280-46da-89f4-6d8051e4aeef

Command/event characteristic:
c5f50002-8280-46da-89f4-6d8051e4aeef

Pybricks WRITE_STDIN command is byte 6. The app writes:

6 + ASCII("m,<throttle>,<steering>\n")

Example:
6 + "m,75,-20\n"

## Android 11

The app targets Android 11 (API 30) and requests ACCESS_FINE_LOCATION for BLE scanning.

## Build

Open this directory in Android Studio.

Build:
Build -> Build Bundle(s) / APK(s) -> Build APK(s)

Install the generated APK on the Android 11 phone.

## Important

Do not keep Pybricks Code connected to the hub while using the Android remote.
The phone should be the BLE host connection.

The app looks specifically for the Pybricks service UUID, so it should find the SPIKE hub automatically.

## Car behavior

Joystick:
- Up = forward
- Down = reverse
- Left = steer left
- Right = steer right
- Release = stop and center steering

Hub ports:
- A = right drive motor
- E = left drive motor, reversed
- C = steering motor
