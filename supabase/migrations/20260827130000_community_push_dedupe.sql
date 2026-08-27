-- Community push stampede + duplicate-notification fix (2026-08-27).
--
-- Two problems, one cause chain:
-- 1) article_comments and forum_comments each had TWO enabled notify triggers:
--    the legacy pair (notify_article_comment / notify_forum_reply) and their
--    superset rewrites (notify_article_favoriters / notify_forum_participants).
--    Every comment double-inserted community_notifications rows, so users saw
--    duplicate bell entries and got duplicate pushes.
-- 2) trg_community_push fires one net.http_post per inserted row. The 07:30
--    generate-bot-comments run inserts ~450 rows within seconds, which
--    502-stormed send-fcm-push (261 dropped pushes on 2026-08-27). Bot activity
--    is already delivered as a per-user digest push by generate-bot-comments,
--    so the per-row push is pure duplication for bot actors.
--
-- Fix: drop the legacy duplicate triggers, and gate the per-row push behind a
-- WHEN clause so it fires only for rows with a real human actor (actor_id set
-- and not an AI companion account). Bot-driven rows still land in the in-app
-- notification feed; their push is the digest. notify_community_push itself is
-- left untouched.

drop trigger if exists on_article_comment_notify on article_comments;
drop function if exists notify_article_comment();

drop trigger if exists on_forum_comment_notify on forum_comments;
drop function if exists notify_forum_reply();

-- True when the actor is a real signed-in human (not null, not a companion bot).
create or replace function public.is_human_actor(p_actor uuid)
returns boolean
language sql
stable
security definer
set search_path to 'public'
as $$
  select p_actor is not null
     and not exists (select 1 from ai_companion_auth a where a.user_id = p_actor);
$$;

drop trigger if exists trg_community_push on community_notifications;
create trigger trg_community_push
  after insert on community_notifications
  for each row
  when (public.is_human_actor(new.actor_id))
  execute function notify_community_push();
