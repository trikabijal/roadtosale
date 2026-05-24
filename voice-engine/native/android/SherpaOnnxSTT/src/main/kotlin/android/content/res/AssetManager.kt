// Stub AssetManager for JVM compilation.
// On Android the real android.content.res.AssetManager is provided by the
// Android runtime. On the JVM (macOS lab) this stub satisfies the import so
// the sherpa-onnx Kotlin API compiles without the Android SDK. The JNI layer
// passes null for assetManager on non-Android hosts (file-based model loading).
package android.content.res

class AssetManager
