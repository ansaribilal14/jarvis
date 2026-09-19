// JARVIS privileged-shell surface over Shizuku's UserService (runs as shell uid 2000).
// argv arrays only - no shell interpretation. stream() never terminates on its own;
// the caller stops it by closing the returned descriptor or calling destroy().
package com.jarvis.mobile.core.shizuku;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IJarvisShellService {
    Bundle exec(in String[] command, long timeoutMillis, int maxOutputBytes) = 1;
    void stream(in String[] command, in ParcelFileDescriptor sink) = 2;
    int uid() = 3;
    void destroy() = 4;
}
