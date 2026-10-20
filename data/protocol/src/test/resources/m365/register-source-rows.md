# Minimal source rows, not upstream code
# findings/NINEBOT_XIAOMI_UPSTREAM_REFERENCE.md:105-141,150-161 (L1 inventory).
# READ rows omit entries with +; WRITE rows are the separately machine-extracted complete list.
READ
| `00` | 2 | – | magic `0x515C` |
| `0D`/`0E`/`0F` | 2 | – | motor phase A/B/C current |
| `10` | 0xE | – | ESC serial number |
| `1A` | 2 | – | ESC firmware version |
| `1B`/`1C`/`1D` | 2 | – | error code / warning code / ESC status |
| `22` | 2 | – | battery level % |
| `24` | 2 | – | remaining mileage ×0.8, km×100 |
| `25` | 2 | – | remaining mileage, km×100 |
| `26` | 2 | – | speed |
| `29` | **4** | – | total mileage, m |
| `2F` | 2 | – | current mileage |
| `32`/`34` | 4 | – | total run time / some run time |
| `3A`/`3B` | 2 | – | trip time |
| `3E` | 2 | – | frame temperature |
| `47` | 2 | – | ESC supply voltage (measured by ESC) |
| `48` | 2 | – | **battery voltage (from BMS)** |
| `50` | 2 | – | **battery current (from BMS)** |
| `65` | 2 | – | average speed |
| `67`/`68` | 2 | – | BMS firmware version / BLE firmware version |
| `B0`–`B3` | 2 each | – | error / warning / status / ? |
| `B4` | 2 | – | battery level % |
| `B5` | 2 | – | **speed, m/h** |
| `B6` | 2 | – | average speed |
| `B7` | **4** | – | total mileage, m |
| `B9` | 2 | – | trip distance, m×10 |
| `BB` | 2 | – | frame temperature |
| `DA` | 0xC | – | MCU UID copy |
WRITE
| `0x17` | 6 | scooter PIN |
| **`0x70`** | 2 | **lock** (write 1) |
| **`0x71`** | 2 | **unlock** (write 1) |
| `0x74` | 2 | `?` |
| **`0x75`** | 2 | **eco mode** |
| `0x78` | 2 | reboot (write 1) |
| **`0x79`** | 2 | **power down** (write 1) |
| `0x7A` | 2 | `?` |
| **`0x7B`** | 2 | **KERS level** |
| **`0x7C`** | 2 | **cruise control enable** |
| **`0x7D`** | 2 | **tail light on** |
| `0xBE` | 2 | previous warning code |
