-- This is not a TwoWaySQL sample, but rather a tip on how to handle cases where '2' is appended to the physical table name.
-- You should fix the column names before fixing the table names.
-- Please handle it at your own risk.

SELECT 'ALTER TABLE ' || table_name || ' RENAME TO ' ||  replace(lower(entity_name),'.','$') as alter_table_name
	  , 'UPDATE public.mendixsystem$entity SET table_name = ''' || replace(lower(entity_name),'.','$') || ''' WHERE id = ''' || id || '''' as update_entity_table
FROM public."mendixsystem$entity"
WHERE replace(lower(entity_name),'.','$')<>lower(table_name);
