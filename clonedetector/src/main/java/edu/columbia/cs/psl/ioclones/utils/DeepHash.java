package edu.columbia.cs.psl.ioclones.utils;

import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cedarsoftware.util.DeepEquals;
import com.cedarsoftware.util.ReflectionUtils;

import edu.columbia.cs.psl.ioclones.config.IOCloneConfig;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
/**
 * 
 * Most of the logic and code of DeepHash is from https://github.com/jdereg/java-util/
 *
 */
public class DeepHash {
	private static final Logger logger = LogManager.getLogger(DeepHash.class);
	
	private static int FLOAT_SCALE = IOCloneConfig.getInstance().getFloatScale();
	
	public static int deepHash(Object obj) {
		Set<Object> visited = new HashSet<>();
        LinkedList<Object> stack = new LinkedList<>();
        stack.addFirst(obj);
        int hash = 0;

        while (!stack.isEmpty())
        {
            obj = stack.removeFirst();
            if (obj == null || visited.contains(obj))
            {
                continue;
            }
            
            visited.add(obj);

            if (obj.getClass().isArray())
            {
                int len = Array.getLength(obj);
                for (int i = 0; i < len; i++)
                {
                    stack.addFirst(Array.get(obj, i));
                }
                continue;
            }

            if (obj instanceof Collection)
            {
                stack.addAll(0, (Collection)obj);
                continue;
            }

            if (obj instanceof Map)
            {
                stack.addAll(0, ((Map)obj).keySet());
                stack.addAll(0, ((Map)obj).values());
                continue;
            }
            
            if ((obj instanceof Double) || (obj instanceof Float)) {
            	double d = Double.valueOf(obj.toString());
            	
            	if (Double.isNaN(d)) {
            		//logger.info("Catch nan: " + d);
            		continue ;
            	} else if (Double.isInfinite(d)) {
            		//logger.info("Catch infinite: " + d);
            		d = Double.MAX_VALUE;
            	}
            	
            	//BigDecimal bd = new BigDecimal(d).setScale(FLOAT_SCALE, BigDecimal.ROUND_HALF_UP);
            	BigDecimal bd = BigDecimal.valueOf(d).setScale(FLOAT_SCALE, BigDecimal.ROUND_HALF_UP);
            	//System.out.println("obj: " + obj + " " + d + " " + bd.doubleValue());
            	Double after = new Double(bd.doubleValue());
            	DoubleWrapper dw = new DoubleWrapper(after);
            	stack.add(dw);
            	continue ;
            }
            
            /*if (obj instanceof Double || obj instanceof Float)
            {
            	// just take the integral value for hashcode
            	// equality tests things more comprehensively
            	stack.add(Math.round(((Number) obj).doubleValue()));
            	continue;
            }*/
            
            if (DeepEquals.hasCustomHashCode(obj.getClass()))
            {   // A real hashCode() method exists, call it.
                hash += obj.hashCode();
                continue;
            }
           
            Collection<Field> fields = ReflectionUtils.getDeepDeclaredFields(obj.getClass());
            for (Field field : fields)
            {
                try
                {
                    stack.addFirst(field.get(obj));
                }
                catch (Exception ignored) { }
            }
        }
        return hash;
	}
	
	public static class DoubleWrapper {
		
		Double data = null;
		
		public DoubleWrapper(Double d) {
			data = d;
		}
		
		@Override
		public boolean equals(Object o) {
			if (!(o instanceof DoubleWrapper))
				return false;
			
			DoubleWrapper dw = (DoubleWrapper) o;
			if (dw.data.equals(this.data)) {
				return true;
			} else {
				return false;
			}
		}
		
		@Override
		public int hashCode() {
			return this.data.hashCode();
		}
	}
}
