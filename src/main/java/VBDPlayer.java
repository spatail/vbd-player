import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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


    private VBDPlayer() {
        mediaPlayerComponent = new AudioMediaPlayerComponent();
        mediaPlayerComponent.getMediaPlayer().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {

            Timer timer;

            @Override
            public void playing(MediaPlayer mediaPlayer) {
                logger.debug("playing: {}", mediaPlayer.getLength());
                SwingUtilities.invokeLater(() -> {
                    timeline.setMaximum((int) mediaPlayer.getLength());
                    timeline.setString(getTrackLength("0:00", mediaPlayer.getLength()));
                });
                timer = new Timer(999, e -> {
                    timeline.setValue(timeline.getValue() + 1000);
                    timeline.setString(getTrackLength(timeline.getString(), 1000));
                });
                timer.start();
            }

            @Override
            public void stopped(MediaPlayer mediaPlayer) {
                logger.debug("Stopped");
                stopTimeline();
            }

            @Override
            public void finished(MediaPlayer mediaPlayer) {
                logger.debug("Finished");
                stopTimeline();
            }

            private void stopTimeline() {
                timer.stop();
                SwingUtilities.invokeLater(() -> {
                    timeline.setValue(0);
                    timeline.setString("0:00");
                });
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
        timeline.setStringPainted(true);
        timeline.setString("0:00");
        timeline.setPreferredSize(new Dimension(200, 40));

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
                    .map(link -> new MediaItem(link.text(), link.attr("abs:href")))
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

            return songLinks.stream()
                    .filter(songLink -> songLink.attr("href").endsWith(".ram"))
                    .map(songLink -> new MediaItem(songLink.text(), songLink.attr("abs:href")))
                    .collect(toList());
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

        MediaItem song = songList.getSelectedValue();

        Document doc;
        try {
            doc = Jsoup.connect(song.getUrl()).ignoreContentType(true).get();

            mediaPlayerComponent.getMediaPlayer().playMedia(doc.text());
        } catch (Exception ex) {
            logger.error("Could not play song: {}", song.getUrl());
        }
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

    private static String getTrackLength(String remainingTimeDisplay, long elapsedTimeInMillis) {
        String[] parts = remainingTimeDisplay.replace("-", "").split(":");
        Duration remaining = Duration.ofMinutes(Long.valueOf(parts[0])).plusSeconds(Long.valueOf(parts[1]));
        Duration elapsedTime = Duration.ofMillis(elapsedTimeInMillis);

        logger.debug("remaining: {}, elapsed: {}", remainingTimeDisplay, elapsedTimeInMillis);

        Duration newRemaining = remaining.minus(elapsedTime);
        long mins = newRemaining.toMinutes();
        long secs = newRemaining.minusMinutes(mins).getSeconds();

        logger.debug("newRemaining: {}:{}", mins, secs);

        return String.format("-%02d:%02d", Math.abs(mins), Math.abs(secs));
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
            return getName();
        }
    }

    public static void main(String[] args) {
        new NativeDiscovery().discover();
        SwingUtilities.invokeLater(VBDPlayer::new);
    }
}
