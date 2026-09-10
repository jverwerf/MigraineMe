-- Blood glucose trigger repair.
-- Apply to BOTH projects (MigraineMe and MeSeries/VertigoMe).
--
-- Problem: the two "Blood glucose" trigger templates point at
-- metric_column = 'value_mgdl', which does not exist on blood_glucose_daily.
-- The real column is value_mmol_l. The metric has therefore never resolved:
-- it renders as a dead row in the trigger picker and is skipped by Insights.
--
-- Fix: repoint at value_mmol_l and move the unit to mmol/L, converting the
-- thresholds (mg/dL / 18.0182). 180 -> 10.0, 70 -> 3.9.
-- user_triggers copies the template at enrol time, so every existing user
-- carrying the broken copy is converted here too.

update trigger_templates
   set metric_column     = 'value_mmol_l',
       unit              = 'mmol/L',
       default_threshold = round((default_threshold / 18.0182)::numeric, 1)
 where metric_table  = 'blood_glucose_daily'
   and metric_column = 'value_mgdl';

update user_triggers
   set metric_column     = 'value_mmol_l',
       unit              = 'mmol/L',
       default_threshold = round((default_threshold / 18.0182)::numeric, 1)
 where metric_table  = 'blood_glucose_daily'
   and metric_column = 'value_mgdl';
