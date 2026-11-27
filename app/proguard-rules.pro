# kotlinx.serialization, OkHttp and Coil ship their own consumer rules.
# Keep serializable model classes and their serializers: they are looked up by name.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
