# The widget runtime's UniFFI bindings call native code through JNA, which finds methods,
# structure fields and callbacks by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.anomalyco.psychoscript.** { *; }
-keep class ai.opencode.widgets.** { *; }
-dontwarn java.awt.**
