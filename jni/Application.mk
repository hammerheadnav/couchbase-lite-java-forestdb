# APP_ABI := armeabi mips armeabi-v7a x86 arm64-v8a x86_64 mips64
APP_ABI := all
APP_PLATFORM := android-19
# it seems no backward compatibility. 
# APP_PLATFORM := android-21
NDK_TOOLCHAIN_VERSION := clang
APP_STL := c++_static
# APP_OPTIM := debug # default is `release`
