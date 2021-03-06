/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.architecture.blueprints.todoapp.statistics;

import android.support.annotation.NonNull;
import android.support.v4.util.Pair;

import com.example.android.architecture.blueprints.todoapp.data.Task;
import com.example.android.architecture.blueprints.todoapp.data.source.TasksRepository;
import com.example.android.architecture.blueprints.todoapp.util.EspressoIdlingResource;
import com.example.android.architecture.blueprints.todoapp.util.schedulers.BaseSchedulerProvider;
import com.google.common.primitives.Ints;

import org.reactivestreams.Publisher;

import java.util.List;

import io.reactivex.Flowable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.BiFunction;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Listens to user actions from the UI ({@link StatisticsFragment}), retrieves the data and updates
 * the UI as required.
 */
public class StatisticsPresenter implements StatisticsContract.Presenter {

    @NonNull
    private final TasksRepository mTasksRepository;

    @NonNull
    private final StatisticsContract.View mStatisticsView;

    @NonNull
    private final BaseSchedulerProvider mSchedulerProvider;

    @NonNull
    private CompositeDisposable mCompositeDisposable;

    public StatisticsPresenter(@NonNull TasksRepository tasksRepository,
                               @NonNull StatisticsContract.View statisticsView,
                               @NonNull BaseSchedulerProvider schedulerProvider) {
        mTasksRepository = checkNotNull(tasksRepository, "tasksRepository cannot be null");
        mStatisticsView = checkNotNull(statisticsView, "statisticsView cannot be null!");
        mSchedulerProvider = checkNotNull(schedulerProvider, "schedulerProvider cannot be null");

        mCompositeDisposable = new CompositeDisposable();
        mStatisticsView.setPresenter(this);
    }

    @Override
    public void subscribe() {
        loadStatistics();
    }

    @Override
    public void unsubscribe() {
        mCompositeDisposable.clear();
    }

    private void loadStatistics() {
        mStatisticsView.setProgressIndicator(true);

        // The network request might be handled in a different thread so make sure Espresso knows
        // that the app is busy until the response is handled.
        EspressoIdlingResource.increment(); // App is busy until further notice

        Flowable<Task> tasks = mTasksRepository
                .getTasks()
                .flatMap(new Function<List<Task>, Publisher<Task>>() {
                    @Override
                    public Publisher<Task> apply(@io.reactivex.annotations.NonNull List<Task> tasks) throws Exception {
                        return Flowable.fromIterable(tasks);
                    }
                });
        Flowable<Long> completedTasks = tasks.filter(new Predicate<Task>() {
            @Override
            public boolean test(@io.reactivex.annotations.NonNull Task task) throws Exception {
                return task.isCompleted();
            }
        }).count().toFlowable();
        Flowable<Long> activeTasks = tasks.filter(new Predicate<Task>() {
            @Override
            public boolean test(@io.reactivex.annotations.NonNull Task task) throws Exception {
                return task.isActive();
            }
        }).count().toFlowable();  //(completed, active) -> Pair.create(active, completed)
        Disposable disposable = Flowable
                .zip(completedTasks, activeTasks, new BiFunction<Long, Long, Pair>() {
                    @Override
                    public Pair<Long,Long> apply(@io.reactivex.annotations.NonNull Long aLong, @io.reactivex.annotations.NonNull Long aLong2) throws Exception {
                        return Pair.create(aLong2, aLong);
                    }
                })
                .subscribeOn(mSchedulerProvider.computation())
                .observeOn(mSchedulerProvider.ui())
                .doFinally(new Action() {
                    @Override
                    public void run() throws Exception {
                            if (!EspressoIdlingResource.getIdlingResource().isIdleNow()) {
                                EspressoIdlingResource.decrement(); // Set app as idle.
                            }
                    }
                })
                .subscribe(
                        // onNext
                        new Consumer<Pair>() {
                            @Override
                            public void accept(Pair pair) throws Exception {
                                mStatisticsView.showStatistics(Ints.saturatedCast((Long) pair.first), Ints.saturatedCast((Long) pair.second));
                            }
                        },
                        // onError
                        new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable throwable) throws Exception {
                                mStatisticsView.showLoadingStatisticsError();
                            }
                        },
                        // onCompleted
                        new Action() {
                            @Override
                            public void run() throws Exception {
                                 mStatisticsView.setProgressIndicator(false);
                            }
                        });
        mCompositeDisposable.add(disposable);
    }
}
