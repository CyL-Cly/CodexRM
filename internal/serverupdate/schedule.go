package serverupdate

import (
	"fmt"
	"strings"
	"time"
	_ "time/tzdata"

	"github.com/robfig/cron/v3"
)

const DefaultCron = "0 3 * * *"
const DefaultTimezone = "Asia/Singapore"

func ParseSchedule(spec, zone string) (*cron.SpecSchedule, error) {
	fields := strings.Fields(spec)
	if len(fields) != 5 || strings.ContainsAny(spec, "=@") {
		return nil, fmt.Errorf("expected a five-field cron expression (minute hour day month weekday)")
	}
	for _, field := range fields {
		if strings.HasPrefix(field, ",") || strings.HasSuffix(field, ",") || strings.Contains(field, ",,") {
			return nil, fmt.Errorf("invalid update cron list %q", field)
		}
	}
	loc, err := time.LoadLocation(zone)
	if err != nil {
		return nil, fmt.Errorf("invalid update timezone: %w", err)
	}
	schedule, err := cron.ParseStandard(spec)
	if err != nil {
		return nil, fmt.Errorf("invalid update cron: %w", err)
	}
	result := schedule.(*cron.SpecSchedule) // A five-field spec always yields a SpecSchedule.
	result.Location = loc
	return result, nil
}

func Due(schedule cron.Schedule, now time.Time) bool {
	minute := now.Truncate(time.Minute)
	return schedule.Next(minute.Add(-time.Second)).Equal(minute)
}
