# Test APK signing

`scenic-test.keystore` is an intentionally public, non-secret debug signing key.
Alias: `androiddebugkey`; store/key password: `android`.
It gives CI test APKs from 0.7.2 onward the same update identity. Never use it for production or Play releases.
Older ephemeral CI debug keys cannot be reproduced. Their installations may require uninstalling once, which clears local settings.
