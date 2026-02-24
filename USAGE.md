# anyapk - Usage Guide

## Installation

1. Transfer this APK to your Android device and install it (you'll need to enable "Install from unknown sources" for this first installation).

## First-Time Setup

### Step 1: Enable Developer Options
1. Go to **Settings → About Phone**
2. Tap **Build Number** 7 times
3. You should see a message saying "You are now a developer"

### Step 2: Enable Wireless Debugging
1. Go to **Settings → System → Developer Options**
2. Scroll down and find **Wireless Debugging**
3. Toggle it **ON**
4. You should see an IP address and port number

### Step 3: Pair anyapk with ADB
1. Open the **anyapk** app
2. Tap **"Open Developer Settings"** to verify wireless debugging is enabled
3. In **Developer Options → Wireless Debugging**, tap **"Pair device with pairing code"**
4. You'll see a 6-digit code and a port number
5. Return to the anyapk app and tap **"Enter Pairing Code"**
6. Enter the 6-digit code and port number
7. Tap **"Pair"**

### Step 4: You're Done!
Once paired successfully, you'll see a green checkmark indicating anyapk is ready to install APKs.

## Using anyapk to Install APKs

### Method 1: From a File Manager
1. Open any file manager app (Files, Solid Explorer, etc.)
2. Navigate to an APK file
3. Tap on the APK file
4. Select **anyapk** from the list of installers
5. Tap **"Install APK"**
6. Done! The app installs without verification

### Method 2: From Downloads
1. Download an APK from any source
2. When the download completes, tap to open it
3. Select **anyapk** as the installer
4. Install!

## Important Notes

### Wireless Debugging Must Stay Enabled
- For anyapk to work, **Wireless Debugging must remain enabled** in Developer Options
- If you disable it, you'll need to pair again

### One-Time Pairing
- You only need to pair once
- The pairing persists across reboots (as long as wireless debugging stays enabled)

### Verified vs Unverified Apps
- anyapk works with **both** verified and unverified APKs
- It doesn't check or care about Google's developer verification

### Security Considerations
- **Only install APKs from sources you trust**
- anyapk bypasses Google's verification, so it's your responsibility to verify the APK is safe
- Always download APKs from reputable sources like F-Droid, official websites, etc.

## Troubleshooting

### "ADB permission not granted" error
- Make sure Wireless Debugging is still enabled
- Try pairing again
- Reboot your device and try again

### "Failed to connect to ADB" error
- Enable Wireless Debugging in Developer Options
- Make sure your device isn't in power-saving mode (it can disable wireless debugging)
- Try toggling Wireless Debugging off and back on

### anyapk doesn't appear when opening an APK
- Make sure you completed the pairing successfully
- Check that anyapk is installed
- Try opening the APK from a different file manager

## Building from Source

To build the APK yourself:

```bash
cd /path/to/anyapk
./gradlew assembleDebug
```

The APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`

To build a release version (requires signing configuration):

```bash
./gradlew assembleRelease
```

## Distribution Strategy

### Getting anyapk to Users

Since anyapk itself will need developer verification to be widely distributed, consider these options:

1. **Verify with Google**: Register as a developer and verify your identity with Google. Once verified, anyapk can be freely distributed and will work on all certified Android devices.

2. **Free Limited Distribution**: Use Google's free "limited distribution" account option for teachers, students, and hobbyists (up to 100 devices).

3. **F-Droid Distribution**: F-Droid could verify their developer identity and distribute anyapk as part of their app store.

4. **Direct ADB Installation**: Power users can install anyapk itself via USB ADB, then use it to install everything else.

## How It Works

1. **LibADB Android**: Provides direct ADB functionality without requiring Shizuku
2. **Local Connection**: Connects to the device's own ADB daemon via localhost:5555
3. **pm install Command**: Uses the Android package manager's install command via ADB
4. **No Root Required**: Works on any device with wireless debugging support (Android 11+)

## Technical Details

- **Minimum Android Version**: Android 6.0 (API 23)
- **Wireless Debugging Required**: Android 11+ (API 30+)
- **Package Name**: com.anyapk.installer
- **Permissions**:
  - INTERNET
  - ACCESS_NETWORK_STATE
  - REQUEST_INSTALL_PACKAGES

## Support & Contributing

- Report issues on GitHub
- Contributions welcome via pull requests
- See README.md for more technical details

---

**Remember**: anyapk is a tool for freedom and choice. Use it responsibly and only install apps from trusted sources!
