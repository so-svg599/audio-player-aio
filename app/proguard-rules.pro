# Règles R8 adaptées de la configuration officielle NewPipe pour NewPipeExtractor/Rhino.
# Rhino est utilisé par l'extracteur ; certains points d'intégration Java SE ne sont pas
# présents sur Android, mais ces classes ne sont pas nécessaires à l'exécution Android.

# Préserver les éléments Rhino qui peuvent être accédés indirectement par l'extracteur.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter { *; }

# Classes Java SE absentes d'Android, référencées par les adaptateurs Rhino non utilisés.
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**

# Préserver les métadonnées utiles aux bibliothèques.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Signatures natives éventuelles.
-keepclasseswithmembernames class * {
    native <methods>;
}
# Classes optionnelles référencées par SMBJ, Mbassador et Commons Net mais absentes d’Android.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn net.engio.mbassy.**
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn sun.security.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
-dontwarn org.apache.commons.net.**

# Préserver les classes utilisées par réflexion et les protocoles SMB/FTP.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.apache.commons.net.** { *; }
