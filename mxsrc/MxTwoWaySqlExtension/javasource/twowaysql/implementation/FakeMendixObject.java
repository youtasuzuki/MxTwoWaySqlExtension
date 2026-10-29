package twowaysql.implementation;

import java.util.Date;
import java.util.List;
import java.util.Map;

import com.mendix.core.CoreException;
import com.mendix.core.objectmanagement.member.MendixObjectReference;
import com.mendix.core.objectmanagement.member.MendixObjectReferenceSet;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

public class FakeMendixObject implements IMendixObject {
	
	private Map<String/*Name*/, Object/*value*/> members = new java.util.LinkedHashMap<String, Object>();

	public List<String> getAttributeNames(IContext arg0) {
		return new java.util.ArrayList<String>(members.keySet());
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getValue(IContext arg0, String arg1) {
		return (T) members.get(arg1);
	}

	@Override
	public void setValue(IContext arg0, String arg1, Object arg2) {
		if (arg2 == null) {
			members.remove(arg1);
		} else {
			members.put(arg1, arg2);
		}
	}

	@Override
	public IMendixObject clone() throws CloneNotSupportedException {
		// TODO 自動生成されたメソッド・スタブ
		// CWE-476: Null Pointer Dereference
		//return null;
		return clone();
	}

	@Override
	public IMendixObject createClone() {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public IMendixIdentifier getChangedBy(IContext arg0) throws CoreException {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public Date getChangedDate(IContext arg0) throws CoreException {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public List<? extends IMendixObjectMember<?>> getChangedMembers(IContext arg0) {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public Date getCreatedDate(IContext arg0) throws CoreException {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public IMendixIdentifier getId() {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public IMendixObjectMember<?> getMember(IContext arg0, String arg1) {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public Map<String, ? extends IMendixObjectMember<?>> getMembers(IContext arg0) {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public IMetaObject getMetaObject() {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public IMendixIdentifier getOwner(IContext arg0) throws CoreException {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public List<? extends IMendixObjectMember<?>> getPrimitives(IContext arg0) {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public List<? extends MendixObjectReferenceSet> getReferenceSets(IContext arg0) {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public List<? extends MendixObjectReference> getReferences(IContext arg0) {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public ObjectState getState() {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public String getType() {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public Map<String, ? extends IMendixObjectMember<?>> getVirtualMembers(IContext arg0) {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public boolean hasChangedByAttribute() {
		// TODO 自動生成されたメソッド・スタブ
		return false;
	}

	@Override
	public boolean hasChangedDateAttribute() {
		// TODO 自動生成されたメソッド・スタブ
		return false;
	}

	@Override
	public boolean hasCreatedDateAttribute() {
		// TODO 自動生成されたメソッド・スタブ
		return false;
	}

	@Override
	public boolean hasDeleteRights(IContext arg0) {
		// TODO 自動生成されたメソッド・スタブ
		return false;
	}

	@Override
	public boolean hasMember(String arg0) {
		// TODO 自動生成されたメソッド・スタブ
		return false;
	}

	@Override
	public boolean hasNullValues(IContext arg0) {
		// TODO 自動生成されたメソッド・スタブ
		return false;
	}

	@Override
	public boolean hasOwnerAttribute() {
		// TODO 自動生成されたメソッド・スタブ
		return false;
	}

	@Override
	public boolean isChanged() {
		// TODO 自動生成されたメソッド・スタブ
		return false;
	}

	@Override
	public boolean isNew() {
		// TODO 自動生成されたメソッド・スタブ
		return false;
	}

	@Override
	public boolean isVirtual(IContext arg0, String arg1) {
		// TODO 自動生成されたメソッド・スタブ
		return false;
	}

	@Override
	public IMendixObjectMember<?> getMember(String arg0) {
		// TODO 自動生成されたメソッド・スタブ
		return null;
	}

	@Override
	public boolean hasChangedMemberValue(IContext arg0) {
		// TODO 自動生成されたメソッド・スタブ
		return false;
	}

}
