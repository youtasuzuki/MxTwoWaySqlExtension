-- #TwoWaySQL.Sample1#
-- This #ModuleName.EntityName# directive is ignored at runtime but is used to verify consistency with the returned Entity during automated testing via the TestTwoWaySqls action.

select
	"name","lastlogin","blocked","active","isanonymous"
from
	"system$user"
-- If you enclose the WHERE clause in 'BEGIN' and 'END', any unnecessary 'AND' is automatically removed when the initial condition is eliminated.
/*BEGIN*/
where
	-- Enclosing the code with 'IF pmb.Param != null' and 'END' causes the condition to be automatically removed from the WHERE clause if the parameter is not specified.
	-- If there are cases where the parameter is not specified, it is strongly recommended to enclose the code with 'IF pmb.Param != null' and 'END'.
	/*IF pmb.Name != null*/
	"name" like  '%' || /*pmb.Name*/'Admin' || '%'
	/*END*/
	/*IF pmb.Blocked != null*/
	and "blocked" = /*pmb.Blocked*/'false'
	/*END*/
	/*IF pmb.Active != null*/
	and "active" = /*pmb.Active*/'true'
	/*END*/
	/*IF pmb.IsAnonymous != null*/
	and "isanonymous" = /*pmb.IsAnonymous*/'false'
	/*END*/
/*END*/
