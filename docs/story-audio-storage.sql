-- Private AAC fragments for song stories. Apply in the AmorX Supabase project before releasing the client.
begin;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('story-audio', 'story-audio', false, 1500000, array['audio/mp4'])
on conflict (id) do update
set public = false,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

create index if not exists stories_audio_path_idx on public.stories ((track ->> 'audio_path'))
where kind = 'song' and (track ->> 'audio_path') is not null;

drop policy if exists "story audio owner can upload" on storage.objects;
create policy "story audio owner can upload"
on storage.objects for insert to authenticated
with check (
    bucket_id = 'story-audio'
    and (storage.foldername(name))[1] = (select auth.uid())::text
    and name ~ '^[0-9a-f-]{36}/[0-9a-f-]{36}\.m4a$'
);

drop policy if exists "story audio follows story visibility" on storage.objects;
create policy "story audio follows story visibility"
on storage.objects for select to authenticated
using (
    bucket_id = 'story-audio'
    and (
        (storage.foldername(name))[1] = (select auth.uid())::text
        -- The query runs as the viewer, so public.stories applies its existing RLS policies.
        or exists (
            select 1
            from public.stories as story
            where story.kind = 'song'
              and story.expires_at > now()
              and story.track ->> 'audio_path' = objects.name
              and story.owner_id::text = (storage.foldername(objects.name))[1]
        )
    )
);

drop policy if exists "story audio owner can delete" on storage.objects;
create policy "story audio owner can delete"
on storage.objects for delete to authenticated
using (
    bucket_id = 'story-audio'
    and (storage.foldername(name))[1] = (select auth.uid())::text
);

commit;
