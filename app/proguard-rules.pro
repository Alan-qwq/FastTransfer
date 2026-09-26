# 保留 Gson 反射所需的泛型与字段信息
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.alan.fasttransfer.core.dto.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
