# Cookie Swapper

Burp Suite extension to automatically replace cookies/headers with fresh values before sending requests. Saves you from manually updating expired session tokens across dozens of Repeater tabs.

## Why?

If you do a lot of web app testing, you know the pain: your session expires, you get new cookies, and now every single Repeater tab has stale tokens. You're retesting a bug, you copy-paste the same cookies into 20 different requests, one by one. It's annoying and wastes time.

Cookie Swapper fixes that. Define your replacement rules once, then every request you send through the plugin gets updated cookies/headers automatically.

## Features

- `Ctrl+Shift+Q` hotkey to send selected request (works like Ctrl+R for Repeater)
- Right-click context menu support
- Cookie and header replacement rules
- Import cookies directly from clipboard (Cookie Editor JSON format)
- Repeater-style tabs for each request
- Send with or without replacements
- Rule names persist across Burp restarts (values cleared for security)

## Install

1. Grab `CookieSwapper.jar` from [Releases](../../releases)
2. Burp > Extensions > Add > Java > select the jar
3. Needs Burp v2023.1+ (Montoya API)

## How to use

1. Open the **Cookie Swapper** tab
2. Add rules — set type (Cookie or Header), name, and value
3. Select any request in Proxy History / Site Map / anywhere, hit `Ctrl+Shift+Q`
4. Request opens in a new tab with replacements applied and response shown
5. Hit **Send** to resend with current rules, or **Send (No Replace)** to send as-is

### Importing cookies

Export cookies from your browser using [Cookie Editor](https://cookie-editor.com/), then click **Import Cookies JSON** in the plugin. It reads from your clipboard. You can merge with existing rules or replace them.

## Build

```
javac -cp montoya-api.jar -d build src/burp/BurpExtender.java
cd build && jar cf ../CookieSwapper.jar burp/
```

## License

MIT
