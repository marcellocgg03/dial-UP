# Security policy

## Supported versions

While DialUP is in beta only the latest release receives security fixes. Please update to the latest release before
you report a problem.

## Reporting a vulnerability

Report it privately. Do not open a public issue, a public pull request or a discussion for a security problem.

Use GitHub private vulnerability reporting: open the **Security** tab of this repository and choose **Report a
vulnerability**, which is the `security/advisories/new` page of this repository. Only the maintainer can read the report.

Please include the app version, what you did, what happened, and the impact you expect. If the report needs a log, read
it first and remove LAN addresses, speaker names and anything else you do not want to share.

## What to expect

This is a single-maintainer project and the response is best effort. You can expect an acknowledgement within about a
week, and after that either a fix or an explanation of why it is not treated as a vulnerability. You will be told when a
fix is released, and credited in the release notes if you wish.

## Scope

In scope:

- the media server and the DIAL servers that the app runs on the Wi-Fi network, including how a request is accepted or
  refused;
- the handling of stream tokens and of the addresses the app is willing to contact (the guarded HTTP client);
- what the event log and **Share log** can contain, in particular anything that should have been redacted;
- the release signing and the integrity of the published downloads.

Out of scope:

- changes in the YouTube protocol, and defects in yt-dlp or ffmpeg that have no impact specific to this app (report them
  upstream);
- an attacker who already controls the phone, or who can install apps on it;
- other devices on the Wi-Fi seeing a live stream address while **LAN access** is switched on; that setting is meant to
  open the bridge to the whole network.

## Design notes for reporters

These are deliberate choices, listed so a report can start from them:

- The app uses plain HTTP, because speakers such as Sonos only speak HTTP on the LAN. The HTTP client used for them
  refuses plain HTTP to anything but a private LAN address given as an IP number: no names, no public hosts, and no
  redirects are followed. Traffic to YouTube and Google uses HTTPS.
- **LAN access** is off by default. In that mode the DIAL servers answer only the phone itself (other addresses get a
  refusal before any request is read), and the bridge does not announce the renderers to the network.
- The media server listens on the phone's Wi-Fi address only, serves a stream only under a random per-stream token in
  its address, accepts GET and HEAD only, and has no route that takes an address or a file path from the caller.
- Release builds contain no debug command receiver and no diagnostics that can be driven over adb.
- The app stores no account, password or credential. It asks for no location, storage or contacts permission.
- Tokens, account and device ids and pairing codes are redacted before they reach the event log. The log stays in the
  app's private storage, and **Share log** hands over only the current log file.
