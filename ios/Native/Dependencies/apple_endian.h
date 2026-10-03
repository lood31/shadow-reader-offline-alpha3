#pragma once

// Darwin's endian.h exists but does not provide the Linux leXXtoh aliases
// expected by eSpeak's compatibility header. Keep this adaptation iOS-only.
#if defined(__APPLE__)
#include <libkern/OSByteOrder.h>
#ifndef le16toh
#define le16toh(value) OSSwapLittleToHostInt16(value)
#endif
#ifndef le32toh
#define le32toh(value) OSSwapLittleToHostInt32(value)
#endif
#endif
