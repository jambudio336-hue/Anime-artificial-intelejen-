# Media catalogue refresh and startup videos

## Startup and dashboard media

The Android release workflow copies the root MP4 assets into the Capacitor web directory. The app uses:

- `video.mp4` for the opening splash sequence.
- `Dashboard.mp4` for the looping dashboard hero background.

Keep the filename casing unchanged: Android assets are case-sensitive. The PWA service worker precaches both files. Its cache namespace must be bumped when shell assets change so installed clients can receive updated media.

## Automatic catalogue refresh

The Android app schedules `SyncWorker` through WorkManager every six hours. The worker refreshes the public Melolo popular catalogue and stores a small metadata snapshot locally. If the source is temporarily unavailable, the app can still display the last successful catalogue.

Episode directories are requested live when a title is opened. Playback URLs are resolved immediately before playback rather than being persisted, because provider stream URLs may expire or rotate.

## Limitations

- Background refresh depends on Android WorkManager scheduling, network availability, battery policy, and the provider being reachable.
- A provider can change its undocumented endpoints or block requests; this cannot be guaranteed to work forever.
- Metadata refresh does not mean new video files are hosted by this app. Video delivery remains with the upstream provider and must respect its access rules and content rights.
- The app does not bypass DRM or paywalls.
