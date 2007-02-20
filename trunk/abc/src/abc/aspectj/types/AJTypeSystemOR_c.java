
package abc.aspectj.types;

import java.util.LinkedList;
import java.util.List;
import java.util.Iterator;

import polyglot.types.MethodInstance;
import soot.javaToJimple.jj.types.JjTypeSystem_c;


public abstract class AJTypeSystemOR_c 
       extends JjTypeSystem_c 
       implements AJTypeSystem {

	public List overrides(MethodInstance mi) {
		List result = new LinkedList();
		for (Iterator ovs = super.overrides(mi).iterator(); ovs.hasNext(); ) {
			MethodInstance mi2 = (MethodInstance) ovs.next();
			if (this.isAccessible(mi2,mi.container().toClass()))
				result.add(mi);
		}
		return result;
	}

}
