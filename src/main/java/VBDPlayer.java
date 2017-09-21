import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.co.caprica.vlcj.component.AudioMediaPlayerComponent;
import uk.co.caprica.vlcj.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.player.MediaPlayer;
import uk.co.caprica.vlcj.player.MediaPlayerEventAdapter;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.*;
import javax.swing.event.ListSelectionListener;

import static java.util.stream.Collectors.toList;

/**
 * Created by spatail on 7/6/17.
 */
public class VBDPlayer {

    private static final Logger logger = LoggerFactory.getLogger(VBDPlayer.class);

    private static final String baseUrl = "http://vibrationsofdoom.com/test/";

    private JList<MediaItem> albumList, songList;
    private JProgressBar timeline;
    private AudioMediaPlayerComponent mediaPlayerComponent;

    private BlockingQueue<Integer> timeQ = new ArrayBlockingQueue<>(30);
    private static int POISON = -1;

    public VBDPlayer() {
        mediaPlayerComponent = new AudioMediaPlayerComponent();
        mediaPlayerComponent.getMediaPlayer().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mediaPlayer) {
                logger.debug("playing: {}", mediaPlayer.getLength());
                SwingUtilities.invokeLater(() -> timeline.setMaximum((int) mediaPlayer.getLength()));
            }

            @Override
            public void timeChanged(MediaPlayer mediaPlayer, long newTime) {
                timeQ.add((int) newTime);
                logger.trace("timeChanged: {}", newTime);
            }

            @Override
            public void stopped(MediaPlayer mediaPlayer) {
                logger.trace("Stopped");
                stopTimeline();
            }

            @Override
            public void finished(MediaPlayer mediaPlayer) {
                logger.trace("Finished");
                stopTimeline();
            }

            private void stopTimeline() {
                timeQ.add(POISON);
            }
        });

        JFrame frame = new JFrame("VBD Player");
        frame.setBounds(100, 100, 600, 400);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setVisible(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                mediaPlayerComponent.release();
                System.exit(0);
            }
        });

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayout(2, 13));

        String letters = "abcdefghijklmnopqrstuvwxyz";
        for (String letter : letters.split("")) {
            JButton button = new JButton(letter);
            button.setPreferredSize(new Dimension(30, 30));
            button.addActionListener(populateAlbums);
            buttonPanel.add(button);
        }

        albumList = new JList<>(new DefaultListModel<MediaItem>());
        albumList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        albumList.getSelectionModel().setValueIsAdjusting(false);
        albumList.setLayoutOrientation(JList.VERTICAL);
        albumList.addListSelectionListener(populateSongs);

        songList = new JList<>(new DefaultListModel<MediaItem>());
        songList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        songList.getSelectionModel().setValueIsAdjusting(false);
        songList.setLayoutOrientation(JList.VERTICAL);
        songList.addListSelectionListener(playSelectedSong);

        JScrollPane albumScroller = new JScrollPane(albumList);
        albumScroller.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        albumScroller.setPreferredSize(new Dimension(400, 400));

        JScrollPane songScroller = new JScrollPane(songList);
        songScroller.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        songScroller.setPreferredSize(new Dimension(400, 400));

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new FlowLayout());
        listPanel.add(albumScroller);
        listPanel.add(songScroller);

        JButton stopButton = new JButton("Stop");
        stopButton.setIcon(new ImageIcon("/Users/spatail/Downloads/stop-circle.png"));
        stopButton.addActionListener(e -> {
            if (mediaPlayerComponent.getMediaPlayer().isPlaying()) {
                mediaPlayerComponent.getMediaPlayer().stop();
            }
        });

        timeline = new JProgressBar();
        timeline.setString("0:00");

        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new FlowLayout());
        controlPanel.add(stopButton);
        controlPanel.add(timeline);

        frame.getContentPane().add(buttonPanel, BorderLayout.NORTH);
        frame.getContentPane().add(listPanel, BorderLayout.CENTER);
        frame.getContentPane().add(controlPanel, BorderLayout.SOUTH);
        frame.pack();
    }

    private Function<String, List<MediaItem>> pageToAlbums = page -> {
        try {
            Document doc = Jsoup.connect(baseUrl + page).get();
            Elements links = doc.getElementsByTag("a");
            return links.stream()
                    .filter(link -> !link.attr("href").endsWith("index.html"))
                    .map(link -> {
                        String albumLinkStr = link.attr("href");
                        if (!albumLinkStr.startsWith("http")) {
                            albumLinkStr = baseUrl + albumLinkStr;
                        }
                        return new MediaItem(link.text(), albumLinkStr);
                    })
                    .collect(toList());
        } catch (Exception e) {
            logger.error("Could not load album={}", page, e);
            return Collections.emptyList();
        }
    };

    private Function<String, List<MediaItem>> pageToSongs = page -> {
        try {
            Document doc = Jsoup.connect(page).get();
            Elements songLinks = doc.getElementsByTag("a");

            page = page.substring(0, page.lastIndexOf("/") + 1);

            List<MediaItem> songFiles = new ArrayList<>(songLinks.size());
            for (Element songLink : songLinks) {
                if (!songLink.attr("href").endsWith(".ram")) continue;
                doc = Jsoup.connect(page + songLink.attr("href")).ignoreContentType(true).get();
                songFiles.add(new MediaItem(songLink.text(), doc.text()));
            }

            return songFiles;
        } catch (Exception e) {
            logger.error("Could not load songs for album={}", page, e);
            return Collections.emptyList();
        }
    };

    private ListSelectionListener populateSongs = e -> {
        if (e.getValueIsAdjusting()) {
            return;
        }

        MediaItem album = albumList.getSelectedValue();
        logger.debug("Selected album: " + album);

        Consumer<List<MediaItem>> songsConsumer = freezeEventsDuringJListModelUpdate(songList, this::populateList);

        new FetchPageDataWorker<>(album.getUrl(), pageToSongs, songsConsumer).execute();
    };

    private ListSelectionListener playSelectedSong = e -> {
        if (e.getValueIsAdjusting()) {
            return;
        }

        if (mediaPlayerComponent.getMediaPlayer().isPlaying()) {
            mediaPlayerComponent.getMediaPlayer().stop();
        }

        new UpdateTimelineWorker(timeQ, timeline).execute();

        MediaItem song = songList.getSelectedValue();

        mediaPlayerComponent.getMediaPlayer().playMedia(song.getUrl());
    };

    private ActionListener populateAlbums = e -> {
        String text = ((JButton) e.getSource()).getText();
        logger.debug("Pressed " + text);

        String page = text.toLowerCase();
        page += page + ".html";

        Consumer<List<MediaItem>> albumsConsumer = freezeEventsDuringJListModelUpdate(albumList, this::populateList);

        new FetchPageDataWorker<>(page, pageToAlbums, albumsConsumer).execute();
    };

    private <T> Consumer<List<T>> freezeEventsDuringJListModelUpdate(JList<T> jList, BiConsumer<JList<T>, List<T>> listPopulator) {
        return (List<T> t) -> {
            ListSelectionListener[] listSelectionListeners = jList.getListSelectionListeners();
            Arrays.stream(listSelectionListeners).forEach(jList::removeListSelectionListener);

            listPopulator.accept(jList, t);

            Arrays.stream(listSelectionListeners).forEach(jList::addListSelectionListener);
        };
    }

    private <T> void populateList(JList<T> jList, List<T> data) {
        ((DefaultListModel) jList.getModel()).removeAllElements();
        data.forEach(datum -> ((DefaultListModel<T>) jList.getModel()).addElement(datum));
    }

    private static class FetchPageDataWorker<T> extends SwingWorker<T, Void> {

        private final String page;
        private final Function<String, T> pageDataMapper;
        private final Consumer<T> pageDataConsumer;

        FetchPageDataWorker(String page, Function<String, T> pageDataMapper, Consumer<T> pageDataConsumer) {
            this.page = page;
            this.pageDataMapper = pageDataMapper;
            this.pageDataConsumer = pageDataConsumer;
        }

        @Override
        protected T doInBackground() throws Exception {
            return pageDataMapper.apply(page);
        }

        @Override
        protected void done() {
            try {
                T pageData = get();
                pageDataConsumer.accept(pageData);
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error fetching page data", e);
            }
        }
    }

    private static class UpdateTimelineWorker extends SwingWorker<Void, Integer> {

        private BlockingQueue<Integer> timeQ;
        private JProgressBar timeline;

        public UpdateTimelineWorker(BlockingQueue<Integer> timeQ, JProgressBar timeline) {
            this.timeQ = timeQ;
            this.timeline = timeline;
        }

        @Override
        protected Void doInBackground() throws Exception {
            int last = 0;
            int time;
            while (!isCancelled() && (time = timeQ.take()) != POISON) {
                if (last == 0) {
                    last = time;
                }
                if ((time - last) >= 1000) {
                    publish(time);
                    last = time;
                }
            }
            publish(POISON);
            logger.trace("Done updating timeline");
            return null;
        }

        @Override
        protected void process(List<Integer> chunks) {
            logger.trace("Received {} time values", chunks.size());
            int time = chunks.get(chunks.size() - 1);
            timeline.setValue(time == POISON ? 0 : time);
        }
    }

    private static class MediaItem {
        private String name;
        private String url;

        MediaItem(String name, String url) {
            this.name = name;
            this.url = url;
        }

        String getName() {
            return name;
        }

        String getUrl() {
            return url;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static void main(String[] args) {
        new NativeDiscovery().discover();
        SwingUtilities.invokeLater(VBDPlayer::new);
    }
}
