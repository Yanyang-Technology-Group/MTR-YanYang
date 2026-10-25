// SPDX-License-Identifier: MIT
//
// The JNI environment version this library reports, with a fallback for the Android NDK.
//
// JNI_VERSION_1_8 is the version the JDK's jni.h defines and the one JNI_OnLoad returns. The
// NDK's jni.h stops at JNI_VERSION_1_6, so referring to 1.8 there is a compile error:
//
//   error: use of undeclared identifier 'JNI_VERSION_1_8'
//
// The macro is a plain version number - 0x00010008 - and returning it from JNI_OnLoad is
// correct on Android regardless of which constants that header happens to name, because the
// version describes the JNI interface the library was written against, not a feature the
// header advertises. Android's runtime accepts it and has done since API 24.
//
// Guarded rather than defined outright so that a header which does define it keeps its own
// value and this cannot drift from the platform's.

#pragma once

#include <jni.h>

#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif
