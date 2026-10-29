package twowaysql.implementation;

import java.util.Map;
import java.util.UUID;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.MendixRuntimeException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

public class InLineCursorLoopHandler {
	TwoWaySqlExecutor twoWaySqlExecutor = null;
	private static ILogNode logger = Core.getLogger("TwoWaySql");

	public static String createInLineCursorLoopHandler(IContext context, String twoWaySQLFileName, IMendixObject parameter) throws Exception {
		InLineCursorLoopHandler inLineCursorLoopHandler = new InLineCursorLoopHandler();

		UUID uuid = UUID.randomUUID();
		String fetchHandle = uuid.toString();
		@SuppressWarnings("unchecked")
		Map<String, InLineCursorLoopHandler> handlers = (Map<String, InLineCursorLoopHandler>)context.getData()
				.get(InLineCursorLoopHandler.class.getName());
		if (handlers == null) {
			handlers = new java.util.WeakHashMap<String, InLineCursorLoopHandler>();
			context.getData().put(InLineCursorLoopHandler.class.getName(), handlers);
		}
		inLineCursorLoopHandler.twoWaySqlExecutor = new TwoWaySqlExecutor();
		inLineCursorLoopHandler.twoWaySqlExecutor.openInLineCursor(context, twoWaySQLFileName, parameter);
		handlers.put(fetchHandle, inLineCursorLoopHandler);
		return fetchHandle;
	}

	public static InLineCursorLoopHandler getInLineCursorLoopHandler(IContext context, String cursorHandle) {
		@SuppressWarnings("unchecked")
		Map<String, InLineCursorLoopHandler> handlers = (Map<String, InLineCursorLoopHandler>)context.getData()
				.get(InLineCursorLoopHandler.class.getName());
		if (handlers == null) {
			throw new MendixRuntimeException("InLineCursorLoopHandler not found for handle: " + cursorHandle);
		}
		InLineCursorLoopHandler inLineCursorLoopHandler = handlers.get(cursorHandle);
		if (inLineCursorLoopHandler == null) {
			throw new MendixRuntimeException("InLineCursorLoopHandler not found for handle: " + cursorHandle);
		}
		return inLineCursorLoopHandler;
	}

	public static boolean closeInLineCursorLoopHandler(IContext context, String cursorHandle) throws Exception {
		@SuppressWarnings("unchecked")
		Map<String, InLineCursorLoopHandler> handlers = (Map<String, InLineCursorLoopHandler>)context.getData()
				.get(InLineCursorLoopHandler.class.getName());
		if (handlers == null) {
			return false;
		}
		InLineCursorLoopHandler inLineCursorLoopHandler = handlers.get(cursorHandle);
		if (inLineCursorLoopHandler == null) {
			return false;
		}
		inLineCursorLoopHandler.twoWaySqlExecutor.closeInLineCursor();
		inLineCursorLoopHandler.twoWaySqlExecutor = null;
		handlers.remove(cursorHandle);
		return true;
	}

	public IMendixObject getNext(IContext context, String cursorHandle, String resultEntityType) throws Exception {
		IMendixObject next = this.twoWaySqlExecutor.readNextInLineCursor(context, resultEntityType);
		return next;
	}
	
	protected void finalize() {
		// Ensure the connection is closed when the wrapper is garbage collected
		if (twoWaySqlExecutor != null) {
			try {
				twoWaySqlExecutor.closeInLineCursor();
				twoWaySqlExecutor = null;
				logger.warn("InLineCursorLoopHandler finalized without being closed. Cursor closed.");
			} catch (Exception e) {
				// Log the exception or handle it as needed
			}
		}
	}
	
}
