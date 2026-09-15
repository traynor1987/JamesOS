# James State v1.1

Offline exploratory rules, not clinical predictions or trained AI. Calibration uses up to 28 past wake dates in the phone timezone. Seven distinct wake dates of valid sleep enable an initial energy estimate when a sleep session ended within the last 36 hours. The most recent daily session is the current input and is excluded from the comparison baseline. Crossing midnight does not discard it; its wake date and age are displayed. The 36-hour expiry is a conservative product freshness limit, not a physiological measurement. Existing imported records count immediately; elapsed days without records do not. The longest sleep session on each wake date is used once, so overlapping sources and naps cannot inflate the day count. This is recorded session duration, not measured sleep quality or sleep-stage analysis.

Energy starts with a deliberately low-confidence comparison against the median recorded session (over an hour below: Low; over an hour above: Good; otherwise Moderate). This is a product heuristic, not a clinically validated threshold or probability. Work, HRV, WHOOP recovery and exercise are not yet inputs to this rule. A manual report from the last 12 hours overrides it, including across midnight.

For each signal, seven historical days pairing sleep with that signal's check-in enable an exploratory nearest-three sleep-duration match. At least two of those reports must agree; otherwise James abstains. All such estimates remain low confidence. No claims of validated accuracy, causality, or AI training are made. Missing subjective signals explicitly request check-ins rather than promising that sleep sync alone will unlock them.

Opening a state check-in captures the displayed prediction, rule version, input records, confidence label and timestamp in the report metadata. Saving records a UserCorrection atomically with the report, including the corrected values. Inputs omit nested report metadata to prevent recursive backup growth. Records use the existing Room model and are included in James exports. No destructive schema migration is used.

Health Connect now reads the preceding 28 days of authorised records using its existing paginated, source-preserving, duplicate-safe importer. Availability depends on providers sharing history and Android's granted access. No extra health permissions are requested.

## v1.2: optional check-ins and automatic body signals

Individual fields are optional. Blank fields do not replace earlier recent reports for other signals; DailyReview reports count too. Fatigue can use the sleep deficit, provider-consistent resting-heart-rate baseline and deduplicated 24-hour exercise intervals. Each of sleep >60 min below baseline, resting heart rate >=5 bpm above baseline, and >=60 minutes recorded exercise adds one exploratory load flag. Zero flags gives Low fatigue, one Moderate, two or more High, only when at least one usable input exists. Missing data is explained and confidence stays Low. These thresholds are unvalidated product heuristics, not medical recommendations or measured stress.

Body load is separate from reported emotional stress. It requires recent resting heart rate, seven previous dates from the same provider, and sleep or exercise input. It shows No clear elevation, Some load, or Elevated. It does not claim to read WHOOP Stress Monitor or Samsung's proprietary stress score. The current importer does not supply those scores. WHOOP OAuth, Recovery and HRV remain future integrations. See https://developer.whoop.com/api/ and https://developer.android.com/health-and-fitness/health-connect/data-types .

Snapshot input metadata includes the contributing health records. Existing backup schemas and history are retained.
