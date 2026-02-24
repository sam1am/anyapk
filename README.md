# anyapk

**Bypass Google And Install Any APK You Want On The Device You Own.**

anyapk is a lightweight Android application installer that bypasses Google's developer verification requirements by using local ADB (Android Debug Bridge) connections. Smoothly install any APK file on your device without restrictions, gatekeepers, or corporate approval.

## Why We Made This

Android devices belong to their users, not corporations or governments. Yet with each passing year, installing applications on your own device becomes more restricted, more cumbersome, and more dependent on the approval of gatekeepers who do not have your best interests at heart.

anyapk returns control to where it belongs: in your hands.

## Manifesto: Application Freedom

When a corporation becomes a gatekeeper, they don't stand alone at the gate. Their partners become gatekeepers. Government jurisdictions become gatekeepers. And suddenly, what began as a "quality control measure" transforms into a lever of control.

This lever has been used by repressive governments to deny citizens access to free communication tools. It has been used to block applications that challenge power structures. It has been used to enforce geographic restrictions that serve business interests rather than user needs.

**Application freedom is user freedom.**

Every barrier placed between you and the software you choose to run is a barrier to your digital autonomy. Every verification requirement is a chokepoint where control can be exercised. Every "safety measure" that requires corporate or governmental approval is a potential tool of censorship.

We built anyapk because we believe:
- Your device belongs to you
- You have the right to run any software you choose
- No corporation or government should stand between you and that choice
- Technical barriers to freedom must be removed, not accepted

If you believe software should serve users rather than control them, anyapk is for you.

## Features

- **One-time setup**: Pair once using wireless debugging, use forever
- **No root required**: Uses Android's built-in wireless ADB
- **No external dependencies**: Everything runs locally on your device
- **System-wide integration**: Register as an APK handler to install from any file manager
- **Direct file selection**: Built-in file picker if you don't have a file manager handy

## Installation

### Method 1: Install via APK (Recommended)

1. Download the latest `anyapk.apk` from the [Releases](../../releases) page
2. Open the APK file on your device
3. Grant installation permissions if prompted
4. Welcome to application freedom

### Method 2: Install via ADB (The Last Time)

If you can't install the APK directly (due to existing restrictions), use ADB from your computer. This is the last time you'll need to do this the hard way.

**Prerequisites:**
- A computer with ADB installed ([Download SDK Platform Tools](https://developer.android.com/studio/releases/platform-tools))
- A USB cable

**Steps:**
1. Enable Developer Options on your device:
   - Go to **Settings → About Phone**
   - Tap **Build Number** 7 times until you see "You are now a developer!"

2. Enable USB Debugging:
   - Go to **Settings → Developer Options**
   - Enable **USB debugging**

3. Connect your device to your computer via USB

4. Install anyapk using ADB:
   ```bash
   adb install anyapk.apk
   ```

5. You're done! You won't need ADB from your computer again.

## Setup & Usage

### First-Time Setup

1. **Enable Developer Options** (if not already enabled):
   - Open **Settings → About Phone**
   - Tap **Build Number** 7 times
   - You'll see "You are now a developer!"

2. **Enable Wireless Debugging**:
   - Go to **Settings → Developer Options**
   - Enable **Wireless debugging**
   - Approve your WiFi network if prompted

3. **Pair anyapk** (one-time only):
   - Open anyapk
   - Tap **Enter Pairing Code**
   - Follow the split-screen instructions:
     1. Tap the **Recent Apps** button (square icon)
     2. Long-press on **Settings**, select **"Open in split screen view"**
     3. Select **anyapk** for the other half of the screen
     4. In Settings: **Developer Options → Wireless Debugging**
     5. Tap **"Pair device with pairing code"**
     6. Enter the pairing code and port in anyapk
     7. Tap **Pair**

4. **Authorize the connection**:
   - You'll see an "Allow USB debugging?" prompt
   - Check **"Always allow from this computer"**
   - Tap **Allow**

That's it! anyapk is now permanently connected and ready to use.

### Installing APK Files

Once paired, installing APK files is effortless:

#### Method 1: From Any File Manager
1. Open any APK file in your file manager, browser, or download folder
2. Select **anyapk** as the installer
3. Tap **Install**
4. Done!

#### Method 2: Using anyapk's Built-in Picker
1. Open anyapk
2. Tap **Select APK to Install**
3. Browse and select your APK file
4. Tap **Install**
5. Done!

## How It Works

anyapk uses LibADB Android to establish a local ADB connection via wireless debugging. Once paired, it maintains the connection and can install any APK file using the ADB install protocol - the same method developers use, but running entirely on your device.

No internet connection required. No cloud services. No remote servers. Just you and your device.

## Technical Details

- **Language**: Kotlin
- **Minimum Android Version**: Android 11 (API 30) - Required for wireless debugging
- **Permissions Required**:
  - `INTERNET` - For local ADB socket connection
  - `ACCESS_NETWORK_STATE` - To detect network availability
  - `REQUEST_INSTALL_PACKAGES` - To initiate APK installations

**Key Dependencies**:
- LibADB Android 3.1.0 - Local ADB client implementation
- Conscrypt 2.5.3 - Secure ADB communication
- sun-security-android 1.1 - RSA key generation for ADB authentication

## Privacy

anyapk runs entirely on your device. It:
- Does not collect any data
- Does not connect to any remote servers
- Does not transmit any information about you or your usage
- Does not track installations

Your activity is your business, not ours.

## Troubleshooting

**"Setup Required" stays visible even after enabling wireless debugging:**
- Make sure wireless debugging is actually ON in Developer Options
- Try restarting anyapk
- Verify you're on WiFi (wireless debugging requires WiFi)

**"Stream closed" error during installation:**
- Close and reopen anyapk to refresh the connection
- Verify wireless debugging is still enabled
- Check that the APK file isn't corrupted

**Can't find the pairing dialog in Settings:**
- Make sure you're in Developer Options → Wireless debugging
- Look for "Pair device with pairing code" button
- If missing, try toggling wireless debugging off and on

**Installation fails with "ADB unauthorized":**
- Unpair the device in Settings → Wireless debugging → Paired devices
- Restart anyapk and pair again
- Make sure to check "Always allow" on the authorization prompt

## License

This project is open source under the Apache 2.0 license. Use it, modify it, share it. Free software for free people.

## Contributing

Contributions are welcome! Whether it's bug fixes, feature additions, or documentation improvements, help us make application freedom more accessible.

## Support

This is a tool built by users, for users. If it helps you, share it with others who value digital freedom.

---

**Remember**: Your device. Your choice. Your freedom.
