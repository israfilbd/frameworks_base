#!/system/bin/sh
# Script to start "requestsync" on the device
#
base=/system
export CLASSPATH=$base/framework/requestsync.jar
exec app_process $base/bin com.android.commands.requestsync.RequestSync "$@"

