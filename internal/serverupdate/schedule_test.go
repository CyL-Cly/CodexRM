package serverupdate

import (
	"testing"
	"time"
)

func TestSchedule(t *testing.T) {
	defaultSchedule, err := ParseSchedule(DefaultCron, DefaultTimezone)
	if err != nil {
		t.Fatal(err)
	}
	morning := time.Date(2026, 11, 23, 19, 0, 0, 0, time.UTC) // 03:00 Singapore time.
	if !Due(defaultSchedule, morning.Add(time.Second)) || Due(defaultSchedule, morning.Add(-time.Second)) || Due(defaultSchedule, morning.Add(time.Minute)) {
		t.Fatal("default does not match exactly the 03:00 minute in Singapore")
	}
	custom, err := ParseSchedule("*/15 2-3 * * MON-FRI", "UTC")
	if err != nil {
		t.Fatal(err)
	}
	if !Due(custom, time.Date(2026, 11, 23, 2, 15, 0, 0, time.UTC)) || Due(custom, time.Date(2026, 11, 22, 2, 15, 0, 0, time.UTC)) {
		t.Fatal("custom schedule did not honor steps, ranges or weekdays")
	}
	for _, spec := range []string{"", "0 3 *, * *", "0 3 * * * *", "CRON_TZ=UTC 0 3 * * *", "@daily", "60 3 * * *"} {
		if _, err := ParseSchedule(spec, "UTC"); err == nil {
			t.Errorf("accepted invalid cron %q", spec)
		}
	}
	if _, err := ParseSchedule(DefaultCron, "Invalid/Zone"); err == nil {
		t.Fatal("accepted invalid timezone")
	}
}
