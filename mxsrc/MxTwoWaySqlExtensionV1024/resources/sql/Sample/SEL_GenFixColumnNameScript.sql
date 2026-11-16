-- This is not a TwoWaySQL sample, but rather a tip on how to handle cases where '2' is appended to the physical column name.
-- You should fix the column names before fixing the table names.
-- Please handle it at your own risk.

SELECT 'ALTER TABLE ' || ent.table_name || ' RENAME COLUMN ' || column_name || ' TO ' || lower(attribute_name) as alter_column_name
	  , 'UPDATE public.mendixsystem$attribute SET column_name = ''' || lower(attribute_name) || ''' WHERE id = ''' || att.id || '''' as update_attribute_table
FROM public."mendixsystem$attribute" att
LEFT OUTER JOIN public."mendixsystem$entity" ent ON ent.id = att.entity_id
WHERE lower(attribute_name)<>lower(column_name);
