-- One team per name in a classroom, whatever the capitals.
--
-- Creating a duplicate was refused but renaming into one was not, so some may exist. The oldest
-- keeps its name; each later one gets its id appended, cut to fit the column. Looped because a
-- suffixed name could, in principle, be one somebody had already typed.

DO $$
DECLARE
    renamed INTEGER;
BEGIN
    LOOP
        UPDATE contest_groups g
           SET name = left(g.name, 120 - length(' (' || g.group_id || ')'))
                      || ' (' || g.group_id || ')'
          FROM (SELECT group_id,
                       row_number() OVER (PARTITION BY classroom_id, lower(name)
                                          ORDER BY group_id) AS position
                  FROM contest_groups) AS ranked
         WHERE ranked.group_id = g.group_id AND ranked.position > 1;
        GET DIAGNOSTICS renamed = ROW_COUNT;
        EXIT WHEN renamed = 0;
        RAISE NOTICE 'Renamed % team(s) that shared a name within a classroom', renamed;
    END LOOP;
END $$;

CREATE UNIQUE INDEX uq_cg_classroom_name ON contest_groups (classroom_id, lower(name));
