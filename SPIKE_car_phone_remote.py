# SPIKE Prime (Pybricks 4.x) - Phone Remote Controlled Car
#
# Ports:
#   A = right drive motor
#   E = left drive motor (reversed)
#   C = steering motor
#
# Phone protocol:
#   The Android app uses the Pybricks GATT WRITE_STDIN command.
#   It writes lines such as:
#
#       m,75,-20\n
#
#   throttle = -100..100
#   steering = -100..100
#
#   throttle: +100 = forward, -100 = reverse
#   steering: -100 = full left, +100 = full right
#
# Releasing the phone joystick sends m,0,0.
# A 600 ms watchdog also stops the car and centers steering if
# commands stop arriving.

from pybricks.hubs import PrimeHub
from pybricks.pupdevices import Motor
from pybricks.parameters import Port, Direction, Stop
from pybricks.tools import wait, StopWatch
import sys
import uselect


hub = PrimeHub()

# ---------------- Motors ----------------

right_motor = Motor(Port.A)

# This motor is physically reversed relative to the right motor.
left_motor = Motor(Port.E, Direction.COUNTERCLOCKWISE)

steering = Motor(Port.C)


# ---------------- Steering calibration ----------------
#
# Find one physical end stop.
steering.run_until_stalled(-200, then=Stop.HOLD, duty_limit=50)

# This end is now 0 temporarily.
steering.reset_angle(0)

# Move to the other physical end and measure the range.
STEER_LIMIT = steering.run_until_stalled(
    200, then=Stop.HOLD, duty_limit=50
)

# Move to the mechanical midpoint.
steering.run_target(300, STEER_LIMIT / 2)

# Define that midpoint as 0 degrees.
steering.reset_angle(0)

# Keep a small safety margin from both physical stops.
STEER_RANGE = max(10, int(STEER_LIMIT / 2) - 5)


# ---------------- Drive settings ----------------

MAX_DRIVE_SPEED = 1200  # deg/s
drive_speed = MAX_DRIVE_SPEED


def clamp(value, low, high):
    return max(low, min(high, value))


def set_drive(throttle):
    """Set both drive motors from -100..100 throttle."""
    throttle = clamp(throttle, -100, 100)

    if throttle == 0:
        right_motor.stop()
        left_motor.stop()
        return

    speed = int(drive_speed * abs(throttle) / 100)

    if throttle > 0:
        speed = speed
    else:
        speed = -speed

    right_motor.run(speed)
    left_motor.run(speed)


last_steering_target = 999


def set_steering(steering_value):
    """Set steering from -100..100."""
    global last_steering_target

    steering_value = clamp(steering_value, -100, 100)

    target = int(STEER_RANGE * steering_value / 100)

    # Don't restart the same steering command unnecessarily.
    if target == last_steering_target:
        return

    last_steering_target = target

    # The joystick's -100 is left and +100 is right.
    steering.run_target(500, target, wait=False)


def stop_car_and_center():
    global last_steering_target

    right_motor.stop()
    left_motor.stop()

    last_steering_target = 999
    steering.run_target(500, 0, wait=False)


# ---------------- Non-blocking stdin ----------------
#
# This is important: readline() by itself blocks forever.
# Polling lets us keep a watchdog running while waiting for
# the phone.

stdin = sys.stdin.buffer
poller = uselect.poll()
poller.register(stdin, uselect.POLLIN)

watchdog = StopWatch()
last_command_time = 0

hub.display.icon([[0, 100, 0, 100, 0]] * 5)
print("READY")
print("STEER_RANGE =", STEER_RANGE)
print("MAX_DRIVE_SPEED =", MAX_DRIVE_SPEED)


def handle_command(line):
    """Handle one complete command line."""
    global last_command_time

    try:
        cmd = line.decode().strip()
    except:
        return

    if not cmd:
        return

    # Main proportional RC command:
    # m,<throttle>,<steering>
    if cmd.startswith("m,"):
        parts = cmd.split(",")

        if len(parts) != 3:
            return

        try:
            throttle = int(parts[1])
            steering_value = int(parts[2])
        except ValueError:
            return

        throttle = clamp(throttle, -100, 100)
        steering_value = clamp(steering_value, -100, 100)

        set_drive(throttle)
        set_steering(steering_value)

        last_command_time = watchdog.time()

        return

    # Optional simple commands for manual testing.
    if cmd == "w":
        set_drive(100)
        last_command_time = watchdog.time()

    elif cmd == "s":
        set_drive(-100)
        last_command_time = watchdog.time()

    elif cmd == "x":
        set_drive(0)
        last_command_time = watchdog.time()

    elif cmd == "a":
        set_steering(-100)
        last_command_time = watchdog.time()

    elif cmd == "d":
        set_steering(100)
        last_command_time = watchdog.time()

    elif cmd == "c":
        set_steering(0)
        last_command_time = watchdog.time()

    elif cmd.startswith("v"):
        # Optional manual speed command.
        # v50 means 50% of MAX_DRIVE_SPEED.
        try:
            pct = clamp(int(cmd[1:]), 10, 100)
            # This changes the global maximum used by subsequent
            # m commands.
            global drive_speed
            drive_speed = int(MAX_DRIVE_SPEED * pct / 100)
        except ValueError:
            pass

    print("OK", cmd)


# ---------------- Main loop ----------------

while True:

    # Process every complete line currently waiting.
    events = poller.poll(0)

    if events:
        while True:
            try:
                line = stdin.readline()
            except:
                line = b""

            if not line:
                break

            handle_command(line)

            # Check if another line is already waiting.
            if not poller.poll(0):
                break

    # Safety watchdog.
    #
    # If the phone stops sending commands, stop the car.
    if watchdog.time() - last_command_time > 600:
        if last_command_time != 0:
            stop_car_and_center()
            last_command_time = 0

    wait(10)
