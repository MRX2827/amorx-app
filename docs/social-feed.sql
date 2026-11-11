begin;

create table public.feed_follows (
    follower_id uuid not null references auth.users(id) on delete cascade,
    author_id uuid not null references auth.users(id) on delete cascade,
    primary key (follower_id, author_id),
    check (follower_id <> author_id)
);
create index feed_follows_author_idx on public.feed_follows (author_id);

create table public.feed_posts (
    id uuid primary key,
    author_id uuid not null references auth.users(id) on delete cascade,
    author_name text not null check (char_length(author_name) between 1 and 80),
    caption text not null default '' check (char_length(caption) <= 1000),
    media_type text not null check (media_type in ('image/jpeg', 'video/mp4', 'video/quicktime', 'video/webm', 'video/3gpp')),
    media_path text not null unique,
    preview_path text unique,
    created_at timestamptz not null default now(),
    check (media_path = author_id::text || '/' || id::text || case media_type
        when 'image/jpeg' then '.jpg' when 'video/mp4' then '.mp4' when 'video/quicktime' then '.mov'
        when 'video/webm' then '.webm' when 'video/3gpp' then '.3gp' end),
    check (preview_path is null or (media_type <> 'image/jpeg' and
        preview_path = author_id::text || '/' || id::text || '-preview.jpg'))
);
create index feed_posts_latest_idx on public.feed_posts (created_at desc, id desc);
create index feed_posts_author_idx on public.feed_posts (author_id, created_at desc, id desc);

create table public.feed_likes (
    post_id uuid not null references public.feed_posts(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    primary key (post_id, user_id)
);

create table public.feed_comments (
    id uuid primary key,
    post_id uuid not null references public.feed_posts(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    author_name text not null check (char_length(author_name) between 1 and 80),
    body text not null check (char_length(btrim(body)) between 1 and 500),
    created_at timestamptz not null default now()
);
create index feed_comments_post_idx on public.feed_comments (post_id, created_at desc, id desc);

alter table public.feed_follows enable row level security;
alter table public.feed_posts enable row level security;
alter table public.feed_likes enable row level security;
alter table public.feed_comments enable row level security;

revoke all on public.feed_follows, public.feed_posts, public.feed_likes, public.feed_comments from public, anon, authenticated;
grant select, insert, delete on public.feed_follows, public.feed_posts, public.feed_likes, public.feed_comments to authenticated;

create policy "feed follows read own" on public.feed_follows for select to authenticated
using (follower_id = (select auth.uid()));
create policy "feed follows add own" on public.feed_follows for insert to authenticated
with check (follower_id = (select auth.uid()) and (
    public.are_friends((select auth.uid()), author_id) or
    exists (select 1 from public.profiles p where p.id = feed_follows.author_id and p.is_public)
));
create policy "feed follows remove own" on public.feed_follows for delete to authenticated
using (follower_id = (select auth.uid()));

create policy "feed posts audience" on public.feed_posts for select to authenticated
using (
    author_id = (select auth.uid())
    or public.are_friends((select auth.uid()), author_id)
    or exists (
        select 1 from public.feed_follows f
        where f.follower_id = (select auth.uid()) and f.author_id = feed_posts.author_id
    )
);
create policy "feed posts add own" on public.feed_posts for insert to authenticated
with check (author_id = (select auth.uid()));
create policy "feed posts remove own" on public.feed_posts for delete to authenticated
using (author_id = (select auth.uid()));

create policy "feed likes visible posts" on public.feed_likes for select to authenticated
using (exists (select 1 from public.feed_posts p where p.id = post_id));
create policy "feed likes add own" on public.feed_likes for insert to authenticated
with check (user_id = (select auth.uid()) and exists (select 1 from public.feed_posts p where p.id = post_id));
create policy "feed likes remove own" on public.feed_likes for delete to authenticated
using (user_id = (select auth.uid()));

create policy "feed comments visible posts" on public.feed_comments for select to authenticated
using (exists (select 1 from public.feed_posts p where p.id = post_id));
create policy "feed comments add own" on public.feed_comments for insert to authenticated
with check (user_id = (select auth.uid()) and exists (select 1 from public.feed_posts p where p.id = post_id));
create policy "feed comments remove own" on public.feed_comments for delete to authenticated
using (user_id = (select auth.uid()));

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('feed-media', 'feed-media', false, 41943040,
    array['image/jpeg', 'video/mp4', 'video/quicktime', 'video/webm', 'video/3gpp']);

create policy "feed storage read" on storage.objects for select to authenticated
using (bucket_id = 'feed-media');
create policy "feed storage owner writes" on storage.objects for all to authenticated
using (bucket_id = 'feed-media' and (storage.foldername(name))[1] = (select auth.uid())::text)
with check (bucket_id = 'feed-media' and (storage.foldername(name))[1] = (select auth.uid())::text);

-- Restrictive policies also keep rules from other buckets from granting access here.
create policy "feed storage audience restriction" on storage.objects as restrictive for select to authenticated
using (bucket_id <> 'feed-media' or (
    (select auth.uid()) is not null and (
        (storage.foldername(name))[1] = (select auth.uid())::text
        or exists (select 1 from public.feed_posts p where p.media_path = objects.name or p.preview_path = objects.name)
    )
));
create policy "feed storage no anonymous access" on storage.objects as restrictive for all to anon
using (bucket_id <> 'feed-media') with check (bucket_id <> 'feed-media');
create policy "feed storage insert restriction" on storage.objects as restrictive for insert
with check (bucket_id <> 'feed-media' or (
    (storage.foldername(name))[1] = (select auth.uid())::text
    and name ~ '^[0-9a-f-]{36}/[0-9a-f-]{36}(-preview)?\.(jpg|mp4|mov|webm|3gp)$'
));
create policy "feed storage update restriction" on storage.objects as restrictive for update
using (bucket_id <> 'feed-media' or (storage.foldername(name))[1] = (select auth.uid())::text)
with check (bucket_id <> 'feed-media' or (
    (storage.foldername(name))[1] = (select auth.uid())::text
    and name ~ '^[0-9a-f-]{36}/[0-9a-f-]{36}(-preview)?\.(jpg|mp4|mov|webm|3gp)$'
));
create policy "feed storage delete restriction" on storage.objects as restrictive for delete
using (bucket_id <> 'feed-media' or (storage.foldername(name))[1] = (select auth.uid())::text);

create function public.feed_page(
    p_author uuid default null, p_before timestamptz default null, p_before_id uuid default null
) returns table (
    id uuid, author_id uuid, author_name text, caption text, media_type text, media_path text,
    preview_path text, created_at timestamptz, likes bigint, comments bigint, liked boolean
)
language sql stable set search_path = ''
as $$
    select p.id, p.author_id, coalesce(nullif(pr.username, ''), p.author_name),
        p.caption, p.media_type, p.media_path, p.preview_path, p.created_at,
        (select count(*) from public.feed_likes l where l.post_id = p.id),
        (select count(*) from public.feed_comments c where c.post_id = p.id),
        exists (select 1 from public.feed_likes l where l.post_id = p.id and l.user_id = (select auth.uid()))
    from (
        select fp.* from public.feed_posts fp
        where (p_author is null or fp.author_id = p_author)
          and (p_before is null or (fp.created_at, fp.id) < (p_before, p_before_id))
        order by fp.created_at desc, fp.id desc
        limit 21
    ) p
    left join public.profiles pr on pr.id = p.author_id
    order by p.created_at desc, p.id desc
$$;

create function public.feed_profile_counts(p_user uuid) returns table (
    followers bigint, following bigint
)
language sql stable security definer set search_path = ''
as $$
    select
        (select count(*) from public.feed_follows f where f.author_id = p_user),
        (select count(*) from public.feed_follows f where f.follower_id = p_user)
    where (select auth.uid()) is not null and (
        p_user = (select auth.uid())
        or public.are_friends((select auth.uid()), p_user)
        or exists (select 1 from public.profiles p where p.id = p_user and p.is_public)
    )
$$;
revoke all on function public.feed_page(uuid, timestamptz, uuid) from public, anon;
revoke all on function public.feed_profile_counts(uuid) from public, anon;
grant execute on function public.feed_page(uuid, timestamptz, uuid) to authenticated;
grant execute on function public.feed_profile_counts(uuid) to authenticated;

commit;
