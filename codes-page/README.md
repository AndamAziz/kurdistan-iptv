# Codes page (andam.uk/gcode)

The owner's page for KURDISTAN IPTV activation codes. It is one static file,
served by nginx from `/var/www/gcode/index.html`. It holds no key: every action
goes to the `kiptv-admin` Supabase function with the owner's password.

## What it shows

- The numbers on top: all codes, active, ending in 7 days, expired or off,
  codes used today, and devices seen today.
- On every code: a bar for each of the last 14 days (devices that day), the
  devices today and this week, and all devices ever seen on it.
- Sorting by most used today or this week.
- **+ Time**: add 1, 3, 6 or 12 months, take off 7 days or a month, set the
  end date, or make it lifetime. The device limit is not changed from here.
- **Devices**: every device on a code, when it was last seen and how many
  checks it made.

The daily numbers come from the `kiptv_daily` table, written by `kiptv_check`
(one row per code, per day in Kurdistan time, per device; kept for 60 days).

## Putting it on the server

```bash
cd /opt/kurdistan-vidaa-src && git pull -q
cp /var/www/gcode/index.html /var/www/gcode/index.html.bak-$(date +%F-%H%M)
cp codes-page/index.html /var/www/gcode/index.html
```
