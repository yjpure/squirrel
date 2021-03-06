package org.squirrelframework.foundation.fsm.impl;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.squirrelframework.foundation.component.SquirrelProvider;
import org.squirrelframework.foundation.component.impl.AbstractSubject;
import org.squirrelframework.foundation.fsm.ActionExecutor;
import org.squirrelframework.foundation.fsm.ActionExecutor.ExecActionLisenter;
import org.squirrelframework.foundation.fsm.ImmutableLinkedState;
import org.squirrelframework.foundation.fsm.ImmutableState;
import org.squirrelframework.foundation.fsm.StateContext;
import org.squirrelframework.foundation.fsm.StateMachine;
import org.squirrelframework.foundation.fsm.StateMachineData;
import org.squirrelframework.foundation.fsm.StateMachineStatus;
import org.squirrelframework.foundation.fsm.TransitionResult;
import org.squirrelframework.foundation.fsm.Visitor;
import org.squirrelframework.foundation.util.Pair;
import org.squirrelframework.foundation.util.TypeReference;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;

/**
 * The Abstract state machine provide several extension ability to cover different extension granularity. 
 * <ol>
 * <li>Method <b>beforeStateExit</b>/<b>afterStateEntry</b> is used to add custom logic on all kinds of state exit/entry.</li>
 * <li>Method <b>exit[stateName]</b>/<b>entry[stateName]</b> is extension method which is used to add custom logic on specific state.</li>
 * <li>Method <b>beforeTransitionBegin</b>/<b>afterTransitionComplete</b> is used to add custom logic on all kinds of transition 
 * accepted all conditions.</li>
 * <li>Method <b>transitFrom[fromStateName]To[toStateName]On[eventName]</b> is used to add custom logic on specific transition 
 * accepted all conditions.</li>
 * <li>Method <b>transitFromAnyTo[toStateName]On[eventName]</b> is used to add custom logic on any state transfer to specific target 
 * state on specific event happens, so as the <b>transitFrom[fromStateName]ToAnyOn[eventName]</b>, <b>transitFrom[fromState]To[ToStateName]</b>, 
 * and <b>on[EventName]</b>.</li>
 * </ol>
 * @author Henry.He
 *
 * @param <T> state machine type
 * @param <S> state type
 * @param <E> event type
 * @param <C> context type
 */
public abstract class AbstractStateMachine<T extends StateMachine<T, S, E, C>, S, E, C> extends AbstractSubject implements StateMachine<T, S, E, C> {
    
    private static final Logger logger = LoggerFactory.getLogger(AbstractStateMachine.class);
    
    private boolean autoStart = true;
    
    private final LinkedList<Pair<E, C>> queuedEvents = Lists.newLinkedList();
    
    private final ActionExecutor<T, S, E, C> executor = SquirrelProvider.getInstance().newInstance(
    		new TypeReference<ActionExecutor<T, S, E, C>>(){});
    
    protected final StateMachineData<T, S, E, C> data;
    
    private E startEvent, finishEvent, terminateEvent;
    
    protected AbstractStateMachine(ImmutableState<T, S, E, C> initialState, Map<S, ImmutableState<T, S, E, C>> states) {
        data = SquirrelProvider.getInstance().newInstance( 
                new TypeReference<StateMachineData<T, S, E, C>>(){}, 
                new Class[]{Map.class}, new Object[]{states} );
        
        S intialStateId = initialState.getStateId();
        data.write().initalState(intialStateId);
        data.write().currentState(intialStateId);
    }
    
    private void processEvent(E event, C context) {
        data.lock();
        ImmutableState<T, S, E, C> fromState = data.read().currentRawState();
        S fromStateId = data.read().currentState();
        logger.debug("Transition from state \""+fromState+"\" on event \""+event+"\" begins.");
        Stopwatch sw = null;
        if(logger.isDebugEnabled()) {
            sw = new Stopwatch().start();
        }
        try {
            beforeTransitionBegin(fromStateId, event, context);
            fireEvent(new TransitionBeginEventImpl<T, S, E, C>(fromStateId, event, context, getThis()));
            
            executor.begin();
            TransitionResult<T, S, E, C> result = FSM.newResult(false, fromState, null);
            fromState.internalFire( FSM.newStateContext(this, data, fromState, event, context, result, executor) );
            executor.execute();
            
            if(result.isAccepted()) {
                data.write().lastState(fromStateId);
                data.write().currentState(result.getTargetState().getStateId());
            	fireEvent(new TransitionCompleteEventImpl<T, S, E, C>(fromStateId, data.read().currentState(), 
                      event, context, getThis()));
                afterTransitionCompleted(fromStateId, data.read().currentState(), event, context);
            } else {
            	fireEvent(new TransitionDeclinedEventImpl<T, S, E, C>(fromStateId, event, context, getThis()));
                afterTransitionDeclined(fromStateId, event, context);
            }
        } catch(Exception e) {
            fireEvent(new TransitionExceptionEventImpl<T, S, E, C>(e, fromStateId, 
                    data.read().currentState(), event, context, getThis()));
            afterTransitionCausedException(e, fromStateId, data.read().currentState(), event, context);
        } finally {
            if(logger.isDebugEnabled()) {
                logger.debug("Transition from state \""+fromState+"\" on event \""+event+
                        "\" tooks "+sw.stop().elapsedMillis()+"ms.");
            }
            data.unlock();
        }
    }
    
    protected int getQueuedEventSize() {
        return queuedEvents.size();
    }
    
    private void processQueuedEvents() {
        while (getQueuedEventSize() > 0) {
            Pair<E, C> eventInfo = queuedEvents.removeFirst();
            processEvent(eventInfo.first(), eventInfo.second());
        }
    }
    
    private void execute() {
        if (isIdel()) {
            try {
                setStatus(StateMachineStatus.BUSY);
                processQueuedEvents();
            } finally {
            	if(getStatus()==StateMachineStatus.BUSY)
            	    setStatus(StateMachineStatus.IDLE);
            }
        }
    }
    
    @Override
    public void fire(E event, C context) {
        if(getStatus()==StateMachineStatus.INITIALIZED) {
            if(autoStart) {
                start(context);
            } else {
                throw new RuntimeException("The state machine is not running.");
            }
        }
        if(getStatus()==StateMachineStatus.TERMINATED) {
            throw new RuntimeException("The state machine is already terminated.");
        }
        queuedEvents.addLast(new Pair<E, C>(event, context));
        execute();
    }
    
    @Override
    public S test(E event, C context) {
        if(data.read().stateMachineStatus()==StateMachineStatus.BUSY || 
           data.read().stateMachineStatus()==StateMachineStatus.ERROR ||
           data.read().stateMachineStatus()==StateMachineStatus.TERMINATED) {
            throw new RuntimeException("Cannot test state machine under "+data.read().stateMachineStatus()+" status.");
        }
        
        S testResult = null;
        StateMachineData.Reader<T, S, E, C> oldData = dumpSavedData();
        executor.setDummyExecution(true);
        try {
            fire(event, context);
            testResult = data.read().currentState();
        } finally {
            queuedEvents.clear();
            loadSavedData(oldData);
            executor.setDummyExecution(false);
        }
        return testResult;
    }
    
    protected boolean isIdel() {
    	return getStatus()!=StateMachineStatus.BUSY;
    }
    
    protected void afterTransitionCausedException(Exception e, S fromState, S toState, E event, C context) {
        setStatus(StateMachineStatus.ERROR);
        logger.error("Transition from state \""+fromState+"\" to state \""+toState+
                "\" on event \""+event+"\" failed, which is caused by exception \""+e.getMessage()+"\".");
    }
    
    protected void beforeTransitionBegin(S fromState, E event, C context) {
    }
    
    protected void afterTransitionCompleted(S fromState, S toState, E event, C context) {
    }
    
    protected void afterTransitionDeclined(S fromState, E event, C context) {
    }
    
    protected void internalSetState(S state) {
        data.write().currentState(state);
    }
    
    @Override
    public ImmutableState<T, S, E, C> getCurrentRawState() {
        return data.read().currentRawState();
    }
    
    @Override
    public ImmutableState<T, S, E, C> getLastRawState() {
        return data.read().lastRawState();
    }
    
    @Override
    public ImmutableState<T, S, E, C> getInitialRawState() {
        return data.read().initialRawState();
    }
    
    @Override
    @Deprecated
    public ImmutableState<T, S, E, C> getRawStateFrom(S stateId) {
        return data.read().rawStateFrom(stateId);
    }
    
    @Override
    public S getCurrentState() {
        return data.read().currentState();
    }
    
    @Override
    public S getLastState() {
        return data.read().lastState();
    }
    
    @Override
    public S getInitialState() {
        return data.read().initialState();
    }

    private void entryAll(ImmutableState<T, S, E, C> origin, StateContext<T, S, E, C> stateContext) {
    	Stack<ImmutableState<T, S, E, C>> stack = new Stack<ImmutableState<T, S, E, C>>();

    	ImmutableState<T, S, E, C> state = origin;
		while (state != null) {
			stack.push(state);
			state = state.getParentState();
		}
		while (stack.size() > 0) {
			state = stack.pop();
			state.entry(stateContext);
		}
	}
    
    @Override
    public void start(C context) {
    	if(isStarted()) {
            return;
        }
    	setStatus(StateMachineStatus.IDLE);
        
    	executor.begin();
    	StateContext<T, S, E, C> stateContext = FSM.newStateContext(
    			this, data, data.read().currentRawState(), getStartEvent(), 
    			context, null, executor);
        entryAll(data.read().initialRawState(), stateContext);
        ImmutableState<T, S, E, C> currentState = data.read().currentRawState();
        ImmutableState<T, S, E, C> historyState = currentState.enterByHistory(stateContext);
        data.write().currentState(historyState.getStateId());
        executor.execute();
        
        execute();
        fireEvent(new StartEventImpl<T, S, E, C>(getThis()));
    }
    
    private boolean isStarted() {
        return getStatus()==StateMachineStatus.IDLE || getStatus()==StateMachineStatus.BUSY;
    }
    
    private boolean isTerminiated() {
    	return getStatus()==StateMachineStatus.TERMINATED;
    }
    
    @Override
    public StateMachineStatus getStatus() {
        return data.read().stateMachineStatus();
    }
    
    protected void setStatus(StateMachineStatus status) {
        data.write().stateMachineStatus(status);
    }
    
    @Override
    public S getLastActiveChildStateOf(S parentStateId) {
    	return data.read().lastActiveChildStateOf(parentStateId);
    }
    
    @Override
    public List<S> getSubStatesOn(S parentStateId) {
        return data.read().subStatesOn(parentStateId);
    }
    
    @Override
    public void terminate(C context) {
    	if(isTerminiated()) {
            return;
        }
        
    	executor.begin();
        StateContext<T, S, E, C> stateContext = FSM.newStateContext(
                this, data, data.read().currentRawState(), getTerminateEvent(), 
                context, null, executor);
        exitAll(data.read().currentRawState(), stateContext);
        executor.execute();
        
        data.write().currentState(data.read().initialState());
        setStatus(StateMachineStatus.TERMINATED);
        fireEvent(new TerminateEventImpl<T, S, E, C>(getThis()));
    }
    
    private void exitAll(ImmutableState<T, S, E, C> current, StateContext<T, S, E, C> stateContext) {
    	ImmutableState<T, S, E, C> state = current;
        while (state != null) {
        	state.exit(stateContext);
        	state = state.getParentState();
		}
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public T getThis() {
    	return (T)this;
    }
    
    void setTypeOfStateMachine(Class<? extends T> stateMachineType) {
        data.write().typeOfStateMachine(stateMachineType);
    }
    
    void setTypeOfState(Class<S> stateType) {
        data.write().typeOfState(stateType);
    }
    
    void setTypeOfEvent(Class<E> eventType) {
        data.write().typeOfEvent(eventType);
    }
    
    void setTypeOfContext(Class<C> contextType) {
        data.write().typeOfContext(contextType);
    }
    
    @Override
    public void accept(Visitor<T, S, E, C> visitor) {
        visitor.visitOnEntry(this);
        for(ImmutableState<T, S, E, C> state : data.read().rawStates()) {
        	if(state.getParentState()==null)
        		state.accept(visitor);
        }
        visitor.visitOnExit(this);
    }
    
    public void setStartEvent(E startEvent) {
    	this.startEvent=startEvent;
    }
    
    public E getStartEvent() {
    	return startEvent;
    }
    
    public void setTerminateEvent(E terminateEvent) {
    	this.terminateEvent=terminateEvent;
    }
    
    public E getTerminateEvent() {
    	return terminateEvent;
    }
    
    public void setFinishEvent(E finishEvent) {
    	this.finishEvent=finishEvent;
    }
    
    public E getFinishEvent() {
    	return finishEvent;
    }
    
    @Override
    public StateMachineData.Reader<T, S, E, C> dumpSavedData() {
        StateMachineData<T, S, E, C> savedData = null;
        if(data.isUnlocked()) {
            savedData = SquirrelProvider.getInstance().newInstance( 
                    new TypeReference<StateMachineData<T, S, E, C>>(){});
            savedData.dump(data.read());
            
            // process linked state if any
            saveLinkedStateData(data.read(), savedData.write());
        } 
        return savedData==null ? null : savedData.read();
    }
    
    private void saveLinkedStateData(StateMachineData.Reader<T, S, E, C> src, StateMachineData.Writer<T, S, E, C> target) {
        dumpLinkedStateFor(src.currentRawState(), target);
//        dumpLinkedStateFor(src.lastRawState(), target);
        // TODO-hhe: dump linked state info for last active child state
        // TODO-hhe: dump linked state info for parallel state
    }
    
    private void dumpLinkedStateFor(ImmutableState<T, S, E, C> rawState, StateMachineData.Writer<T, S, E, C> target) {
        if(rawState!=null && rawState instanceof ImmutableLinkedState) {
            ImmutableLinkedState<T, S, E, C> linkedRawState = (ImmutableLinkedState<T, S, E, C>)rawState;
            StateMachineData.Reader<? extends StateMachine<?, S, E, C>, S, E, C> linkStateData = 
                    linkedRawState.getLinkedStateMachine().dumpSavedData();
            target.linkedStateDataOn(rawState.getStateId(), linkStateData);
        }
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public boolean loadSavedData(StateMachineData.Reader<T, S, E, C> savedData) {
        Preconditions.checkNotNull(savedData, "Saved data cannot be null");
        if(data.isUnlocked()) {
            data.dump(savedData);
            
            // process linked state if any
            for(S linkedState : savedData.linkedStates()) {
                StateMachineData.Reader linkedStateData = savedData.linkedStateDataOf(linkedState);
                ImmutableState<T, S, E, C> rawState = data.read().rawStateFrom(linkedState);
                if(linkedStateData!=null && rawState instanceof ImmutableLinkedState) {
                    ImmutableLinkedState<T, S, E, C> linkedRawState = (ImmutableLinkedState<T, S, E, C>)rawState;
                    linkedRawState.getLinkedStateMachine().loadSavedData(linkedStateData);
                }
            }
            return true;
        }
        return false;
    }
    
    @Override
    public boolean isContextSensitive() {
        return true;
    }
    
    @Override
    public void addStateMachineListener(StateMachineListener<T, S, E, C> listener) {
        addListener(StateMachineEvent.class, listener, StateMachineListener.STATEMACHINE_EVENT_METHOD);
    }
    
    @Override
    public void removeStateMachineListener(StateMachineListener<T, S, E, C> listener) {
        removeListener(StateMachineEvent.class, listener, StateMachineListener.STATEMACHINE_EVENT_METHOD);
    }
    
    @Override
    public void addStartListener(StartListener<T, S, E, C> listener) {
        addListener(StartEvent.class, listener, StartListener.START_EVENT_METHOD);
    }
    
    @Override
    public void removeStartListener(StartListener<T, S, E, C> listener) {
        removeListener(StartEvent.class, listener, StartListener.START_EVENT_METHOD);
    }
    
    @Override
    public void addTerminateListener(TerminateListener<T, S, E, C> listener) {
        addListener(TerminateEvent.class, listener, TerminateListener.TERMINATE_EVENT_METHOD);
    }
    
    @Override
    public void removeTerminateListener(TerminateListener<T, S, E, C> listener) {
        removeListener(TerminateEvent.class, listener, TerminateListener.TERMINATE_EVENT_METHOD);
    }
    
    @Override
    public void addStateMachineExceptionListener(StateMachineExceptionListener<T, S, E, C> listener) {
        addListener(StateMachineExceptionEvent.class, listener, 
                StateMachineExceptionListener.STATEMACHINE_EXCEPTION_EVENT_METHOD);
    }
    
    @Override
    public void removeStateMachineExceptionListener(StateMachineExceptionListener<T, S, E, C> listener) {
        removeListener(StateMachineExceptionEvent.class, listener, 
                StateMachineExceptionListener.STATEMACHINE_EXCEPTION_EVENT_METHOD);
    }
    
    @Override
    public void addTransitionBeginListener(TransitionBeginListener<T, S, E, C> listener) {
        addListener(TransitionBeginEvent.class, listener, 
                TransitionBeginListener.TRANSITION_BEGIN_EVENT_METHOD);
    }
    
    @Override
    public void removeTransitionBeginListener(TransitionBeginListener<T, S, E, C> listener) {
        removeListener(TransitionBeginEvent.class, listener, 
                TransitionBeginListener.TRANSITION_BEGIN_EVENT_METHOD);
    }
    
    @Override
    public void addTransitionCompleteListener(TransitionCompleteListener<T, S, E, C> listener) {
        addListener(TransitionCompleteEvent.class, listener, 
                TransitionCompleteListener.TRANSITION_COMPLETE_EVENT_METHOD);
    }
    
    @Override
    public void removeTransitionCompleteListener(TransitionCompleteListener<T, S, E, C> listener) {
        removeListener(TransitionCompleteEvent.class, listener, 
                TransitionCompleteListener.TRANSITION_COMPLETE_EVENT_METHOD);
    }
    
    @Override
    public void addTransitionExceptionListener(TransitionExceptionListener<T, S, E, C> listener) {
        addListener(TransitionExceptionEvent.class, listener, 
                TransitionExceptionListener.TRANSITION_EXCEPTION_EVENT_METHOD);
    }
    
    @Override
    public void removeTransitionExceptionListener(TransitionExceptionListener<T, S, E, C> listener) {
        removeListener(TransitionExceptionEvent.class, listener, 
                TransitionExceptionListener.TRANSITION_EXCEPTION_EVENT_METHOD);
    }
    
    @Override
    public void addTransitionDeclinedListener(TransitionDeclinedListener<T, S, E, C> listener) {
        addListener(TransitionDeclinedEvent.class, listener, 
                TransitionDeclinedListener.TRANSITION_DECLINED_EVENT_METHOD);
    }
    
    @Override
    public void removeTransitionDecleindListener(TransitionDeclinedListener<T, S, E, C> listener) {
        removeListener(TransitionDeclinedEvent.class, listener, 
                TransitionDeclinedListener.TRANSITION_DECLINED_EVENT_METHOD);
    }
    
    public void addExecActionListener(ExecActionLisenter<T, S, E, C> listener) {
    	executor.addExecActionListener(listener);
    }
	
	public void removeExecActionListener(ExecActionLisenter<T, S, E, C> listener) {
		executor.removeExecActionListener(listener);
	}
    
    public static abstract class AbstractStateMachineEvent<T extends StateMachine<T, S, E, C>, S, E, C> 
    implements StateMachine.StateMachineEvent<T, S, E, C> {
    	private final T stateMachine;
        public AbstractStateMachineEvent(T stateMachine) {
        	this.stateMachine = stateMachine;
        }
        
        @Override
        public T getStateMachine() {
            return stateMachine;
        }
    }
    
    public static class StartEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
    extends AbstractStateMachineEvent<T, S, E, C> implements StateMachine.StartEvent<T, S, E, C> {
        public StartEventImpl(T source) {
            super(source);
        }
    }
    
    public static class TerminateEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
    extends AbstractStateMachineEvent<T, S, E, C> implements StateMachine.TerminateEvent<T, S, E, C> {
        public TerminateEventImpl(T source) {
            super(source);
        }
    }
    
    public static class StateMachineExceptionEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
    extends AbstractStateMachineEvent<T, S, E, C> implements StateMachine.StateMachineExceptionEvent<T, S, E, C> {
        private final Exception e;
        public StateMachineExceptionEventImpl(Exception e, T source) {
            super(source);
            this.e = e;
        }
        
        @Override
        public Exception getException() {
            return e;
        }
    }
    
    public static abstract class AbstractTransitionEvent<T extends StateMachine<T, S, E, C>, S, E, C> 
    extends AbstractStateMachineEvent<T, S, E, C> implements StateMachine.TransitionEvent<T, S, E, C> {
        private final S sourceState;
        private final E event;
        private final C context;
        public AbstractTransitionEvent(S sourceState, E event, C context, T stateMachine) {
            super(stateMachine);
            this.sourceState = sourceState;
            this.event = event;
            this.context = context;
        }
        
        @Override
        public S getSourceState() {
            return sourceState;
        }
        
        @Override
        public E getCause() {
            return event;
        }
        
        @Override
        public C getContext() {
            return context;
        }
    }
    
    public static class TransitionBeginEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
    extends AbstractTransitionEvent<T, S, E, C> implements StateMachine.TransitionBeginEvent<T, S, E, C> {
        public TransitionBeginEventImpl(S sourceState, E event, C context,T stateMachine) {
            super(sourceState, event, context, stateMachine);
        }
    }
    
    public static class TransitionCompleteEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
    extends AbstractTransitionEvent<T, S, E, C> implements StateMachine.TransitionCompleteEvent<T, S, E, C> {
        private final S targetState;
        public TransitionCompleteEventImpl(S sourceState, S targetState, E event, C context,T stateMachine) {
            super(sourceState, event, context, stateMachine);
            this.targetState = targetState;
        }
        
        @Override
        public S getTargetState() {
            return targetState;
        }
    }
    
    public static class TransitionExceptionEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
    extends AbstractTransitionEvent<T, S, E, C> implements StateMachine.TransitionExceptionEvent<T, S, E, C> {
        private final S targetState;
        private final Exception e;
        public TransitionExceptionEventImpl(Exception e, S sourceState, S targetState, E event, C context,T stateMachine) {
            super(sourceState, event, context, stateMachine);
            this.targetState = targetState;
            this.e = e;
        }
        
        @Override
        public S getTargetState() {
            return targetState;
        }
        
        @Override
        public Exception getException() {
            return e;
        }
    }
    
    public static class TransitionDeclinedEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
    extends AbstractTransitionEvent<T, S, E, C> implements StateMachine.TransitionDeclinedEvent<T, S, E, C> {
        public TransitionDeclinedEventImpl(S sourceState, E event, C context,T stateMachine) {
            super(sourceState, event, context, stateMachine);
        }
    }
}
