-- Dark-mode thresholds that onboarding derived on the old scale.
--
-- 20260822300000 moved the plain defaults (14 h high / 2 h low) onto the new
-- meaning of the metric — hours of screen-on time with dark mode active rather
-- than a share of the 24-hour day — but onboarding does not write the plain
-- default for everyone. DeterministicMapper derives a band from the high/low
-- pair: center = (high + low) / 2, delta = (high - low) / 2, then shifts it by a
-- sensitivity multiplier taken from how certain the user was. On the old pair
-- that produced 12.8 and 15.2 (82 users on the MigraineMe project), values no
-- phone can reach now, so for those users the dark-mode prodrome could never
-- fire again.
--
-- The rescale recovers each user's multiplier from the old band and replays it
-- against the new one, so their own answer is preserved rather than flattened
-- to the default:
--     m   = (old - 8) / 6          old band: centre 8, delta 6
--     new = 2.25 + m * 1.75        new band: centre 2.25, delta 1.75
-- 12.8 becomes 3.65 and 15.2 becomes 4.35, exactly what today's mapper writes.
--
-- Bounded to values only the old scale could produce, so it is safe to re-run.

update public.user_prodromes
   set default_threshold = round((2.25 + ((default_threshold - 8.0) / 6.0) * 1.75)::numeric, 2)
 where label = 'Dark mode high'
   and default_threshold > 6;

update public.user_prodromes
   set default_threshold = round((2.25 - ((8.0 - default_threshold) / 6.0) * 1.75)::numeric, 2)
 where label = 'Dark mode low'
   and default_threshold > 1.5;
