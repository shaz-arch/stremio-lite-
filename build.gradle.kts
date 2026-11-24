plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    // Checks the shared code only uses Java that Android 5.1 (the 2016 Fire Stick) has.
    id("ru.vyarus.animalsniffer") version "1.7.1" apply false
}
