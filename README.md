# xyzreader

Android application which presents the user with a selection of stories
and which will transition into a detail screen with the story when selected.
Note that this is for udacity nanodegree course on modifying an app to 
leverage material design

## Installation

First, clone this repo. Second, this project developed with Android Studio 1.5.1 and is set up to support 
API 16 or later android OS.


## Usage

Usage should be self-explanitory. Launch app and you will see a recycler
view of stories. Select a story and detail screen will show.

## Design Choices

General:
I threw out a bunch of code that was duplicating google material design library functionality. I did not
throw out code which was not being used or which did not duplicate material design.

I also threw out the custom font. Used Roboto.

List Screen:
I kept the card items for the list layout rather than going for a flat design. In my opinion, the news
stories are distinct, seperate stories. So they can each have their own card. They are linked through
being in the same gridview list and sharing scroll movement. 

Google play newstand (the closest GMS app to match) also follows this approach where each story has its
own card in the list.

Detail Screen:
Detail screen runs in two modalities. As a full screen page for smaller screens (N4->N7). And as a card
view for larger screens (N9, N10).

It is a matter of preference but for text heavy application like a news story, putting a cardview on N7 
shrinks the area for text too much (looks weird/narrow). Thus I made the breakpoint between N7 and N9.

For the full screen detail view, I removed the nav button. There is a clear back button in the nav bar already
and since there is no nav tray for this app, it did not seem appropriate to have two back buttons doing same function.

For the cardview there were several design choices relating back to the background of the view. The 3 choices
I evaluated were:
- pop the cardview on top of the list view. Did not like this as your eye was not drawn to the story (the screen
had too much information on it).
- put a background up which was the image and put cardview on top. Also did not like this because of the color bleed
of cardview image against background (especially on saturated block of color images like the yellow story). Now, I 
actually think this could work if I blurred the background image and muted it. Did not try this but it could be a nice
option (of course blurring means renderscript which means old devices might have perf problems)
- I finally settled on a modification of the first option (putting cardview on top of list view) but dimming the background.
view. This provided continuity, let me re-use a lot of code, is performant on old devices and is not busy on the eyes.

For the cardview, I did re-add a nav bar with a back button. Once you put up a window, it is not intuitive to have
to look outside the window to the nav bar to go back.

## Opens (TODOs)

- The db queries very inneficient (not using query - instead walking the database). Could be improved. This though exists
from original code and with small number of records, not really an issue.
- A blurred image background might be better for the background when on large format devices on detail view (see above).
- And there is still a intermittent issue with scrolling to the proper area of the recycler view when the main activity 
has been killed and the user has paged through the detail view to bring them to a view which was not on original recycler
view screen (i.e. open the first item detail, page to the last item, rotate screen, go back). You get an animation but
often the animation will not go back to the proper recycler view element. Testing google apps, they also have this
problem though. See below for more.

## Note on a troublesome area of the code

Everything was straightforward except the case where the main activity is killed while you are on a detail screen (for
example, if you rotated). The big problem was the transition going back. At the time activity reenter is called,
none of the views are bound yet to recycler view. Thus any transition is killed.

It was pretty painful correcting for this. Ideally there would be some single event/callback I could use to tell me
when recycler view bindings were complete and I could then both scroll the view as necessary and call the transition
code to execute.

Could not find a good event. So instead, I checked in the preDraw listener to see if we had a bound adapter for
recyclerview. If not, I set a semaphore and posted a runnable thread to scroll the view to the proper position.
And in onViewAttachWindow, used the semaphore to call into the transition handler. This type of issue probably has 
a better solution since virtually every app that implements shared transitions will run into it. (and if not handled,
you simply won't get a transition after rotate - maybe that is what is commonly done?).

One problem area that remains is scrolling to the proper position when recycler view has to scroll for this case. I think
this is a common problem though. In testing google apps, google photos simply does not do a transition in this case 
(where recycler view would have to scroll). For gmail, a transition is done but it also has the same scrolling issue
that xyzreader has.
## Testing

Tested on Nexus4 (emulator), N5, N6, N7, N9. Portrait and landscape.
Tested on API Level 23 and API Level 16
Requires GMS/Google play services

## Credits

Original xyzreader codebase
https://github.com/alexjlockwood/activity-transitions - great example of transition with pager
http://developer.android.com/training/animation/screen-slide.html - great example of pager transitions
Udacity courses
Udacity nanodegree forums for fixing up the httpok client crash
Previous projects for android nanodegree
