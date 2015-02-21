package net.sourceforge.vrapper.eclipse.interceptor;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.sourceforge.vrapper.eclipse.activator.VrapperPlugin;
import net.sourceforge.vrapper.eclipse.extractor.EditorExtractor;
import net.sourceforge.vrapper.eclipse.platform.EclipseBufferAndTabService;
import net.sourceforge.vrapper.eclipse.utils.Utils;
import net.sourceforge.vrapper.log.VrapperLog;
import net.sourceforge.vrapper.platform.VrapperPlatformException;
import net.sourceforge.vrapper.vim.EditorAdaptor;
import net.sourceforge.vrapper.vim.Options;
import net.sourceforge.vrapper.vim.modes.NormalMode;

import org.eclipse.core.commands.operations.IOperationHistory;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.MultiEditor;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.AbstractTextEditor;

/**
 * Listener which adds an {@link InputInterceptor} from the underlying factory
 * to editors.
 * 
 * @author Matthias Radig
 */
public class InputInterceptorManager implements IPartListener2, IPageChangedListener, BufferManager {

    public static final InputInterceptorManager INSTANCE = new InputInterceptorManager(
            VimInputInterceptorFactory.INSTANCE);

    private static final Method METHOD_GET_PAGE_COUNT = getMultiPartEditorMethod("getPageCount");
    private static final Method METHOD_GET_EDITOR = getMultiPartEditorMethod(
            "getEditor", Integer.TYPE);

    /** Helper class which initializes the Vrapper machinery for each given editor. */
    private final InputInterceptorFactory factory;

    /**
     * Map of BufferAndTabServices, one per workbench window. This allows to have a buffer list
     * and "current editor" specific to each window.
     */
    private Map<IWorkbenchWindow, EclipseBufferAndTabService> bufferAndTabServices;

    /** Map holding all currently active editors and their associated Vrapper machinery. */
    private final Map<IWorkbenchPart, InputInterceptor> interceptors;

    /**
     * Map holding nested editor info for all activated top-level editors. Stored here so that we
     * don't need to build this every time we come across an editor.
     */
    private final Map<IWorkbenchPart, EditorInfo> toplevelEditorInfo;

    /**
     * Flag which (temporarily) suspends the partActivated method when we know the activated part
     * will immediately change.
     */
    private boolean activationListenerEnabled = true;

    /**
     * Buffer ids for all top-level editor references.
     * Note that some of these editor references might point to a MultiPageEditor, a fact which we
     * can't detect at startup time without forcing all editor plugins to load (we prefer being
     * wrong rather than slowing down Eclipse on startup due to forced loading of all plugins).
     * As a result, we might assign a single id to a MultiPageEditor when that id will be
     * invalidated later.
     */
    protected WeakHashMap<IEditorReference,BufferInfo> reservedBufferIdMapping =
            new WeakHashMap<IEditorReference, BufferInfo>();

    /**
     * Buffer ids for all files which have been opened once in an active editor.
     * <p>This Map can contain buffer information for files which are no longer open.
     */
    protected WeakHashMap<IEditorInput,BufferInfo> activeBufferIdMapping =
            new WeakHashMap<IEditorInput, BufferInfo>();

    /** Buffer ID generator. */
    protected final static AtomicInteger BUFFER_ID_SEQ = new AtomicInteger();

    protected InputInterceptorManager(InputInterceptorFactory factory) {
        this.factory = factory;
        this.bufferAndTabServices = new WeakHashMap<IWorkbenchWindow, EclipseBufferAndTabService>();
        this.interceptors = new WeakHashMap<IWorkbenchPart, InputInterceptor>();
        this.toplevelEditorInfo = new WeakHashMap<IWorkbenchPart, EditorInfo>();
    }

    public EclipseBufferAndTabService ensureBufferService(IEditorPart editor) {
        IWorkbenchWindow window = editor.getEditorSite().getWorkbenchWindow();
        EclipseBufferAndTabService batservice;
        if (bufferAndTabServices.containsKey(window)) {
            batservice = bufferAndTabServices.get(window);
        } else {
            batservice = new EclipseBufferAndTabService(window, this);
            bufferAndTabServices.put(window, batservice);
        }
        return batservice;
    }

    public void interceptWorkbenchPart(IEditorPart part, EditorInfo nestingInfo,
            ProcessedInfo processedInfo) {

        registerEditorPart(nestingInfo, part, false);

        if (part instanceof AbstractTextEditor) {
            AbstractTextEditor editor = (AbstractTextEditor) part;
            interceptAbstractTextEditor(editor, nestingInfo);
        } else if (part instanceof MultiPageEditorPart) {
            try {
                MultiPageEditorPart mPart = (MultiPageEditorPart) part;
                int pageCount = ((Integer) METHOD_GET_PAGE_COUNT.invoke(part)).intValue();
                for (int i = 0; i < pageCount; i++) {
                    IEditorPart subPart = (IEditorPart) METHOD_GET_EDITOR.invoke(mPart, i);
                    if (subPart == null || processedInfo.isProcessed(subPart)) {
                        continue;
                    }
                    if (subPart != null) {
                        interceptWorkbenchPart(subPart, nestingInfo.createChildInfo(subPart), processedInfo.markPart(subPart));
                    }
                }
            } catch (Exception exception) {
                VrapperLog.error("Exception during opening of MultiPageEditorPart",
                        exception);
            }
        } else if (part instanceof MultiEditor) {
            for (IEditorPart subPart : ((MultiEditor) part).getInnerEditors()) {
                if (subPart == null || processedInfo.isProcessed(subPart)) {
                    continue;
                }
                interceptWorkbenchPart(subPart, nestingInfo.createChildInfo(subPart), processedInfo.markPart(subPart));
            }
        } else {
            IExtensionRegistry registry = Platform.getExtensionRegistry();
            IConfigurationElement[] configurationElements = registry
                    .getConfigurationElementsFor("net.sourceforge.vrapper.eclipse.extractor");
            for (IConfigurationElement element: configurationElements) {
                EditorExtractor extractor = (EditorExtractor) Utils
                        .createGizmoForElementConditionally(
                                part, "part-must-subclass",
                                element, "extractor-class");
                if (extractor != null) {
                    for (AbstractTextEditor ate: extractor.extractATEs(nestingInfo)) {
                        interceptAbstractTextEditor(ate, nestingInfo);
                        registerEditorPart(nestingInfo, ate, false);
                    }
                }
            }
        }
    }

    private void interceptAbstractTextEditor(AbstractTextEditor editor, EditorInfo partInfo) {
        if (interceptors.containsKey(editor)) {
            return;
        }
        try {
            Method me = AbstractTextEditor.class.getDeclaredMethod("getSourceViewer");
            me.setAccessible(true);
            Object viewer = me.invoke(editor);
            if (viewer != null) {
                // test for needed interfaces
                ITextViewer textViewer = (ITextViewer) viewer;
                ITextViewerExtension textViewerExt = (ITextViewerExtension) viewer;
                EclipseBufferAndTabService batService = ensureBufferService(editor);
                InputInterceptor interceptor = factory.createInterceptor(editor, textViewer,
                        partInfo, batService);
                CaretPositionHandler caretPositionHandler = interceptor.getCaretPositionHandler();
                CaretPositionUndoHandler caretPositionUndoHandler = interceptor.getCaretPositionUndoHandler();
                SelectionVisualHandler visualHandler = interceptor.getSelectionVisualHandler();
                interceptor.getEditorAdaptor().addVrapperEventListener(interceptor.getCaretPositionUndoHandler());

                textViewerExt.prependVerifyKeyListener(interceptor);
                textViewer.getTextWidget().addMouseListener(caretPositionHandler);
                textViewer.getTextWidget().addCaretListener(caretPositionHandler);
                textViewer.getSelectionProvider().addSelectionChangedListener(visualHandler);
                IOperationHistory operationHistory = PlatformUI.getWorkbench().getOperationSupport().getOperationHistory();
                operationHistory.addOperationHistoryListener(caretPositionUndoHandler);
                interceptors.put(editor, interceptor);
                VrapperPlugin.getDefault().registerEditor(editor, interceptor.getEditorAdaptor());
            }
        } catch (Exception exception) {
            VrapperLog.error("Exception when intercepting AbstractTextEditor",
                    exception);
        }
    }

    public void partClosed(IEditorPart part, EditorInfo nestingInfo, ProcessedInfo processedInfo) {

        InputInterceptor interceptor = interceptors.remove(part);
        // remove the listener in case the editor gets cached
        if (interceptor != null) {
            try {
                Method me = AbstractTextEditor.class.getDeclaredMethod("getSourceViewer");
                me.setAccessible(true);
                Object viewer = me.invoke(part);
                // test for needed interfaces
                ITextViewer textViewer = (ITextViewer) viewer;
                ITextViewerExtension textViewerExt = (ITextViewerExtension) viewer;
                CaretPositionHandler caretPositionHandler = interceptor.getCaretPositionHandler();
                CaretPositionUndoHandler caretPositionUndoHandler = interceptor.getCaretPositionUndoHandler();
                SelectionVisualHandler visualHandler = interceptor.getSelectionVisualHandler();
                textViewerExt.removeVerifyKeyListener(interceptor);
                textViewer.getTextWidget().removeCaretListener(caretPositionHandler);
                textViewer.getTextWidget().removeMouseListener(caretPositionHandler);
                textViewer.getSelectionProvider().removeSelectionChangedListener(visualHandler);
                IOperationHistory operationHistory = PlatformUI.getWorkbench().getOperationSupport().getOperationHistory();
                operationHistory.removeOperationHistoryListener(caretPositionUndoHandler);
            } catch (Exception exception) {
                VrapperLog.error("Exception during closing IWorkbenchPart",
                        exception);
            }
        }
        if (part instanceof IEditorPart) {
            VrapperPlugin.getDefault().unregisterEditor((IEditorPart) part);
        }
        if (part instanceof MultiPageEditorPart) {
            try {
                MultiPageEditorPart mPart = (MultiPageEditorPart) part;
                int pageCount = ((Integer) METHOD_GET_PAGE_COUNT.invoke(part)).intValue();
                for (int i = 0; i < pageCount; i++) {
                    IEditorPart subPart = (IEditorPart) METHOD_GET_EDITOR.invoke(mPart, i);
                    if (subPart == null || processedInfo.isProcessed(subPart)) {
                        continue;
                    }
                    partClosed(subPart, nestingInfo.getChild(subPart), processedInfo.markPart(subPart));
                }
            } catch (Exception exception) {
                VrapperLog.error("Exception during closing MultiPageEditorPart",
                        exception);
            }
        } else if (part instanceof MultiEditor) {
            for (IEditorPart subPart : ((MultiEditor) part).getInnerEditors()) {
                if (subPart == null || processedInfo.isProcessed(subPart)) {
                    continue;
                }
                partClosed(subPart, nestingInfo.createChildInfo(subPart), processedInfo.markPart(subPart));
            }
        }
    }

    public void partActivated(IWorkbenchPart part, EditorInfo nestingInfo, ProcessedInfo processedInfo) {

        InputInterceptor input = interceptors.get(part);

        if (input == null) {
            try {
                if (part instanceof MultiPageEditorPart) {
                    MultiPageEditorPart mPart = (MultiPageEditorPart) part;
                    int activePage = mPart.getActivePage();
                    int pageCount = ((Integer) METHOD_GET_PAGE_COUNT.invoke(part)).intValue();
                    for (int i = 0; i < pageCount; i++) {
                        IEditorPart subPart = (IEditorPart) METHOD_GET_EDITOR.invoke(mPart, i);
                        if (subPart == null || processedInfo.isProcessed(subPart)) {
                            continue;
                        }
                        partActivated(subPart, nestingInfo.getChild(subPart), processedInfo.markPart(subPart));
                    }
                    if (activePage != -1) {
                        IEditorPart curEditor = (IEditorPart) METHOD_GET_EDITOR.invoke(mPart, activePage);
                        if (curEditor != null) {
                            ensureBufferService(mPart).setCurrentEditor(nestingInfo.getChild(curEditor), curEditor);
                        }
                    }
                }
                else if (part instanceof MultiEditor) {
                    MultiEditor mEditor = (MultiEditor) part;
                    for (IEditorPart subPart : mEditor.getInnerEditors()) {
                        if (subPart == null || processedInfo.isProcessed(subPart)) {
                            continue;
                        }
                        partActivated(subPart, nestingInfo.getChild(subPart), processedInfo.markPart(subPart));
                    }
                    IEditorPart curEditor = mEditor.getActiveEditor();
                    if (curEditor != null) {
                        ensureBufferService(mEditor).setCurrentEditor(nestingInfo.getChild(curEditor), curEditor);
                    }
                }
            }
            catch (Exception exception) {
                VrapperLog.error("Exception activating MultiPageEditorPart", exception);
            }
        }
        else {
            //changing tab back to existing editor, should we return to NormalMode?
            EditorAdaptor vim = input.getEditorAdaptor();
            if (VrapperPlugin.isVrapperEnabled()
                    && vim.getConfiguration().get(Options.START_NORMAL_MODE)) {
                vim.setSelection(null);
                vim.changeModeSafely(NormalMode.NAME);
            }
            // Simple editors are marked as active here. Multi-page editors should set their
            // current editor once after calling recursively (see above).
            if (nestingInfo.isSimpleEditor()) {
                IEditorPart editor = (IEditorPart) part;
                ensureBufferService(editor).setCurrentEditor(nestingInfo, editor);
            }
        }
    }

    private static Method getMultiPartEditorMethod(String name,
            Class<?>... args) {
        try {
            Method m = MultiPageEditorPart.class.getDeclaredMethod(name, args);
            m.setAccessible(true);
            return m;
        } catch (Exception exception) {
            VrapperLog.error("Exception extracting MultiPageEditorPart method",
                    exception);
        }
        return null;
    }

    public Iterable<InputInterceptor> getInterceptors() {
        return interceptors.values();
    }

    @Override
    public void partActivated(IWorkbenchPartReference partRef) {
        if ( ! activationListenerEnabled) {
            return;
        }
        IWorkbenchPart part = partRef.getPart(false);
        if (part instanceof IEditorPart) {
            IEditorPart editor = (IEditorPart) part;
            EditorInfo nestedInfo = toplevelEditorInfo.get(editor);
            partActivated(editor, nestedInfo, new ProcessedInfo(editor));
        }
    }

    @Override
    public void partBroughtToTop(IWorkbenchPartReference partRef) {
    }

    @Override
    public void partClosed(IWorkbenchPartReference partRef) {
        IWorkbenchPart part = partRef.getPart(false);
        if (part instanceof IEditorPart) {
            IEditorPart editor = (IEditorPart) part;
            EditorInfo nestedInfo = toplevelEditorInfo.get(editor);
            partClosed(editor, nestedInfo, new ProcessedInfo(editor));
            toplevelEditorInfo.remove(editor);
        }
    }

    @Override
    public void partDeactivated(IWorkbenchPartReference partRef) {
    }

    @Override
    public void partOpened(IWorkbenchPartReference partRef) {
        IWorkbenchPart part = partRef.getPart(false);
        if (part instanceof IEditorPart) {
            IEditorPart editor = (IEditorPart) part;
            EditorInfo nestedInfo = new EditorInfo(editor);
            toplevelEditorInfo.put(editor, nestedInfo);
            interceptWorkbenchPart(editor, nestedInfo, new ProcessedInfo(editor));
        }
    }

    @Override
    public void partHidden(IWorkbenchPartReference partRef) {
    }

    @Override
    public void partVisible(IWorkbenchPartReference partRef) {
    }

    @Override
    public void partInputChanged(IWorkbenchPartReference partRef) {
        final IWorkbenchPart part = partRef.getPart(true);
        // The underlying editor has changed for the part -- reset Vrapper's
        // editor-related references.
        if (part instanceof IEditorPart) {
            IEditorPart editor = (IEditorPart) part;
            EditorInfo editorInfo = toplevelEditorInfo.get(editor);
            if (editorInfo != null) {
                partClosed(editor, editorInfo, new ProcessedInfo(editor));
            }
            interceptWorkbenchPart(editor, editorInfo, new ProcessedInfo(editor));
        }
    }

    @Override
    public void pageChanged(PageChangedEvent event) {
        if ( ! activationListenerEnabled) {
            return;
        }
        if (event.getPageChangeProvider() instanceof IEditorPart
                && event.getSelectedPage() instanceof IEditorPart) {
            IEditorPart editor = (IEditorPart) event.getSelectedPage();
            InputInterceptor interceptor = interceptors.get(editor);
            if (interceptor != null) {
                EditorInfo info = interceptor.getEditorInfo();
                partActivated(editor, info, new ProcessedInfo(editor));
                ensureBufferService(editor).setCurrentEditor(info, editor);
            }
        }
    }

    /* Buffer ID managing code */

    public void registerEditorRef(IEditorReference ref) {
        if ( ! reservedBufferIdMapping.containsKey(ref)) {
            int bufferId = BUFFER_ID_SEQ.incrementAndGet();
            reservedBufferIdMapping.put(ref, new BufferInfo(bufferId, ref, ref.getId()));
        }
    }

    public void registerEditorPart(EditorInfo nestingInfo, IEditorPart editorPart,
            boolean updateLastSeen) {
        IEditorInput input = editorPart.getEditorInput();

        // Spotted in the wild, some child editors of a multi-page editor don't have an input.
        if (input == null) {
            return;
        }

        IWorkbenchPage page = editorPart.getEditorSite().getPage();
        // Remove any lingering references in case input was opened in two different editors.
        BufferInfo reservedBuffer = reservedBufferIdMapping.remove(
                page.getReference(nestingInfo.getTopLevelEditor())); // TODO cache reference

        if ( ! activeBufferIdMapping.containsKey(input)) {
            IEditorInput parentInput = null; // Not needed in case of simple editor
            int id;
            if (nestingInfo.isSimpleEditor()) {
                // Always use existing id if present.
                if (reservedBuffer == null) {
                    id = BUFFER_ID_SEQ.incrementAndGet();
                } else {
                    id = reservedBuffer.bufferId;
                }
            } else {
                // Each child buffer gets its own id.
                id = BUFFER_ID_SEQ.incrementAndGet();
                // Nested editors don't return reliable info, ask parent editor.
                parentInput = nestingInfo.getTopLevelEditor().getEditorInput();
            }
            String parentType = nestingInfo.getTopLevelEditor().getEditorSite().getId();
            BufferInfo bufferInfo = new BufferInfo(id, editorPart, parentInput, parentType, input);
            if (reservedBuffer != null) {
                bufferInfo.seenWindows.putAll(reservedBuffer.seenWindows);
            }
            activeBufferIdMapping.put(input, bufferInfo);
        } else {
            // Verify if editorinput is still being edited in the same editor. It's possible that
            // a file is reopened in another editor, e.g. through "Open with" or a multipage editor.
            BufferInfo bufferInfo = activeBufferIdMapping.get(input);
            IEditorPart lastSeenEditor = bufferInfo.lastSeenEditor.get();

            if ( ! editorPart.equals(lastSeenEditor) && updateLastSeen) {
                if (nestingInfo.isSimpleEditor()) {
                    bufferInfo.parentInput = null;
                } else {
                    bufferInfo.parentInput = nestingInfo.getParentInfo().getCurrent().getEditorInput();
                }
                bufferInfo.editorType = nestingInfo.getTopLevelEditor().getEditorSite().getId();
                bufferInfo.lastSeenEditor = new WeakReference<IEditorPart>(editorPart);
            }
            bufferInfo.seenWindows.put(editorPart.getEditorSite().getWorkbenchWindow(), null);
        }
    }

    public BufferInfo getBuffer(IEditorInput editorInput) {
        return activeBufferIdMapping.get(editorInput);
    }

    public List<BufferInfo> getBuffers() {
        SortedMap<Integer, BufferInfo> bufferMap = new TreeMap<Integer, BufferInfo>();
        for (BufferInfo refBuffer : reservedBufferIdMapping.values()) {
            bufferMap.put(refBuffer.bufferId, refBuffer);
        }
        for (BufferInfo inputBuffer : activeBufferIdMapping.values()) {
            bufferMap.put(inputBuffer.bufferId, inputBuffer);
        }
        return new ArrayList<BufferInfo>(bufferMap.values());
    }
    
    public void activate(BufferInfo buffer) {
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        if (buffer.reference != null) {
            IEditorPart editor = buffer.reference.getEditor(true);
            if (editor == null) {
                throw new VrapperPlatformException("Failed to activate editor for reference "
                        + buffer.reference);
            }
            // Open the reference in its own page, duplicating an unloaded reference is risky.
            buffer.reference.getPage().activate(editor);

        } else if (buffer.input != null && buffer.parentInput == null) {
            try {
                page.openEditor(buffer.input, buffer.editorType, true,
                        IWorkbenchPage.MATCH_ID | IWorkbenchPage.MATCH_INPUT);
            } catch (PartInitException e) {
                throw new VrapperPlatformException("Failed to activate editor for input "
                    + buffer.input + ", type " + buffer.editorType, e);
            }
        } else if (buffer.input != null) {
            IEditorPart parentEditor;
            // Disable listener - multi-page editors can start with any page active so triggering
            // partActivated listeners only clobbers the current editor status.
            activationListenerEnabled = false;
            try {
                IEditorReference[] editors = page.findEditors(buffer.parentInput, buffer.editorType,
                        IWorkbenchPage.MATCH_ID | IWorkbenchPage.MATCH_INPUT);
                // Directly activate existing editors as some editor implementations tend to reset
                // the cursor when "re-opened".
                if (editors.length > 0) {
                    parentEditor = editors[0].getEditor(true);
                    page.activate(parentEditor);
                } else {
                    parentEditor = page.openEditor(buffer.parentInput, buffer.editorType, true,
                        IWorkbenchPage.MATCH_ID | IWorkbenchPage.MATCH_INPUT);
                }
            } catch (PartInitException e) {
                throw new VrapperPlatformException("Failed to activate editor for input "
                    + buffer.input + ", type " + buffer.editorType, e);
            } finally {
                activationListenerEnabled = true;
            }
            EditorInfo parentEditorInfo = toplevelEditorInfo.get(parentEditor);
            activateInnerEditor(buffer, parentEditor, parentEditorInfo);
        } else {
            throw new VrapperPlatformException("Found bufferinfo object with no editor input info!"
                    + " This is most likely a bug.");
        }
    }

    protected void activateInnerEditor(BufferInfo buffer, IEditorPart parentEditor,
            EditorInfo parentEditorInfo) {
        if (parentEditor instanceof MultiPageEditorPart) {
            MultiPageEditorPart multiPage = (MultiPageEditorPart) parentEditor;
            IEditorPart[] foundEditors = multiPage.findEditors(buffer.input);
            if (foundEditors.length < 1) {
                throw new VrapperPlatformException("Failed to find inner editor for "
                        + buffer.input + " in parent editor " + parentEditor);
            }
            IEditorPart editor = foundEditors[0];
            int activePage = multiPage.getActivePage();
            boolean activated = false;
            // Check if the current page is matching our target page. If so, don't activate it again
            // so that the editor won't reset cursor position (as seen in the XML editors)
            if (activePage != -1) {
                IEditorPart innerEditor;
                try {
                    innerEditor = (IEditorPart) METHOD_GET_EDITOR.invoke(multiPage, activePage);
                } catch (Exception e) {
                    throw new VrapperPlatformException("Failed to get active page of " + multiPage, e);
                }
                if (innerEditor != null && innerEditor.getEditorInput().equals(buffer.input)) {
                    EditorInfo info = parentEditorInfo.getChild(innerEditor);
                    // Update active editor info because no listener was called.
                    ensureBufferService(multiPage).setCurrentEditor(info, innerEditor);
                    activated = true;
                }
            }
            if ( ! activated) {
                // Current buffer info will be set through page change listener.
                multiPage.setActiveEditor(editor);
            }
        } else if (parentEditor instanceof MultiEditor) {
            MultiEditor editor = (MultiEditor) parentEditor;
            IEditorPart[] innerEditors = editor.getInnerEditors();
            int i = 0;
            while (i < innerEditors.length
                    && ! buffer.input.equals(innerEditors[i].getEditorInput())) {
                i++;
            }
            if (i < innerEditors.length) {
                IEditorPart innerEditor = innerEditors[i];
                editor.activateEditor(innerEditor);
                // Explicitly set active editor because we don't have a listener here
                EditorInfo editorInfo = parentEditorInfo.getChild(innerEditor);
                partActivated(innerEditor, editorInfo, new ProcessedInfo(innerEditor));
                ensureBufferService(editor).setCurrentEditor(editorInfo, innerEditor);
            }
        }
    }
}
