-keep class com.k2fsa.sherpa.onnx.** { *; }
# Keep the API33 implementation isolated from the API29 service, including its session adapter.
-keep class be.arte.localdictate.insertion.Api33DirectEditor** { *; }
# Preserve versioned entry point and passive API33-only callback types as separate classes.
-keep class be.arte.localdictate.insertion.Api33InsertionService { *; }
-keep class be.arte.localdictate.insertion.GuardedInputMethod** { *; }
