# Third-party notices

Barq is original work by Sajed Al-Abadla. It does not contain code from Fossify Messages
or any other SMS app; it uses the following libraries and assets under their own licenses:

| Component | License |
|---|---|
| AndroidX (Compose, Room, Paging, WorkManager, DataStore, Glance, Navigation, Lifecycle, Core, Activity) | Apache License 2.0 |
| Dagger / Hilt | Apache License 2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache License 2.0 |
| Material Icons (via Compose) | Apache License 2.0 |
| Readex Pro font (Thomas Jockin et al.) | SIL Open Font License 1.1 — see `docs/FONT_LICENSE_OFL.txt` |

The MMS PDU decoder in `app/src/main/java/dev/saged/barq/mms/MmsPdu.kt` was written for Barq
from the public OMA-MMS-ENC and WAP-230-WSP specifications.
