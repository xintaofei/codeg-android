package com.easytier.jni

/** Minimal declarations for the public EasyTier JNI ABI; loading is lazy. */
internal object EasyTierJNI {
    init { System.loadLibrary("easytier_android_jni") }
    @JvmStatic external fun runNetworkInstance(config: String): Int
    @JvmStatic external fun deleteNetworkInstance(instanceName: String): Int
}
